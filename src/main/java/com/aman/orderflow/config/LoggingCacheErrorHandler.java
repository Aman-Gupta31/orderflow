package com.aman.orderflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Redis is a performance optimization here (cache-aside), not a source of truth -
 * the database always is. If Redis is unreachable, every get/put/evict call that
 * Spring's cache abstraction would normally make throws; without this handler,
 * that exception propagates out of the annotated method and takes the whole
 * request down with it, even though the database could have answered it just
 * fine. This handler logs the failure and lets the annotated method run as if
 * no caching were configured at all - a cache miss (or non-cache) is always
 * safe, just slower.
 */
public class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache GET failed for cache '{}' key '{}' - falling through to the database: {}",
                cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn("Cache PUT failed for cache '{}' key '{}': {}", cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Cache EVICT failed for cache '{}' key '{}': {}", cache.getName(), key, exception.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Cache CLEAR failed for cache '{}': {}", cache.getName(), exception.getMessage());
    }
}
