package com.aman.orderflow.config;

import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.repository.ProductRepository;
import com.aman.orderflow.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Simulates a total Redis outage (every cache operation throws) and proves
 * ProductService.getById still returns the correct product instead of a 500 -
 * see docs/learning-guide/06-redis-caching.md. This is the automated
 * counterpart to manually stopping the local Redis service and re-curling the
 * endpoint (also done once, live - see docs/TESTING.md).
 */
@SpringBootTest(classes = CacheResilienceTest.TestConfig.class)
class CacheResilienceTest {

    @Configuration
    @EnableCaching
    static class TestConfig implements CachingConfigurer {
        @Bean
        ProductRepository productRepository() {
            return mock(ProductRepository.class);
        }

        @Bean
        InventoryService inventoryService() {
            return mock(InventoryService.class);
        }

        @Bean
        ProductService productService(ProductRepository productRepository, InventoryService inventoryService) {
            return new ProductService(productRepository, inventoryService);
        }

        @Override
        @Bean
        public CacheManager cacheManager() {
            return new CacheManager() {
                @Override
                public Cache getCache(String name) {
                    return new AlwaysThrowingCache(name);
                }

                @Override
                public Collection<String> getCacheNames() {
                    return List.of("products");
                }
            };
        }

        @Override
        @Bean
        public CacheErrorHandler errorHandler() {
            return new LoggingCacheErrorHandler();
        }
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void getById_stillReturnsTheProduct_whenEveryCacheOperationThrows() {
        Product widget = new Product("Widget", "desc", "SKU-1", new BigDecimal("9.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(widget));

        Product result = productService.getById(1L);

        assertThat(result).isSameAs(widget);
    }

    private static class AlwaysThrowingCache implements Cache {
        private final String name;

        AlwaysThrowingCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getNativeCache() {
            return this;
        }

        @Override
        public ValueWrapper get(Object key) {
            throw new RuntimeException("Simulated Redis outage");
        }

        @Override
        public <T> T get(Object key, @Nullable Class<T> type) {
            throw new RuntimeException("Simulated Redis outage");
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw new RuntimeException("Simulated Redis outage");
        }

        @Override
        public void put(Object key, Object value) {
            throw new RuntimeException("Simulated Redis outage");
        }

        @Override
        public void evict(Object key) {
            throw new RuntimeException("Simulated Redis outage");
        }

        @Override
        public void clear() {
            throw new RuntimeException("Simulated Redis outage");
        }
    }
}
