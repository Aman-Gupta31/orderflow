package com.aman.orderflow.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Cache-aside: a @Cacheable method checks Redis first; on a miss it runs the
 * method body (hits the database) and writes the result back before returning.
 * TTL-based expiry (not manual invalidation of every possible stale path) is the
 * primary staleness defense - see docs/learning-guide/06-redis-caching.md for the
 * full policy, including what happens on a cache miss vs a Redis outage.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private final RedisConnectionFactory connectionFactory;
    private final long productCacheTtlSeconds;

    public CacheConfig(RedisConnectionFactory connectionFactory,
                        @Value("${app.cache.product-ttl-seconds:300}") long productCacheTtlSeconds) {
        this.connectionFactory = connectionFactory;
        this.productCacheTtlSeconds = productCacheTtlSeconds;
    }

    @Override
    @Bean
    public CacheManager cacheManager() {
        // GenericJackson2JsonRedisSerializer's no-arg constructor builds its own
        // ObjectMapper with two things already set up: java.time support is NOT
        // one of them (Instant on Product failed to serialize until
        // findAndRegisterModules() was added below - a real bug found live), and
        // "default typing" IS one of them - it writes an embedded "@class" hint
        // into every cached value so deserialize() later knows which Java type
        // to reconstruct instead of defaulting to a generic LinkedHashMap.
        // Supplying a *custom* ObjectMapper (as done here, to fix the java.time
        // gap) replaces the whole mapper, including that default-typing setup -
        // it does not merge with it. Omitting activateDefaultTyping below caused
        // a second real bug, caught live: cached reads threw
        // ClassCastException (LinkedHashMap cannot be cast to Product) the
        // moment Redis came back up after an outage and a genuine cache hit was
        // served. Both bugs, and this fix, are documented in
        // docs/learning-guide/06-redis-caching.md.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();
        ObjectMapper redisObjectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(productCacheTtlSeconds))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(valueSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .build();
    }

    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
