package com.aman.orderflow.product;

import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.dto.ProductUpdateRequest;
import com.aman.orderflow.product.repository.ProductRepository;
import com.aman.orderflow.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Proves cache-aside behavior (@Cacheable/@CacheEvict actually intercept calls
 * through Spring AOP) using an in-memory ConcurrentMapCacheManager instead of
 * real Redis - this test is about "does the caching abstraction work," which is
 * backend-agnostic, not about Redis-specific behavior (TTL expiry, serialization
 * format), which is verified against a real local Redis instance separately -
 * see docs/TESTING.md.
 */
@SpringBootTest(classes = ProductServiceCacheTest.TestConfig.class)
class ProductServiceCacheTest {

    @Configuration
    @EnableCaching
    static class TestConfig {
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

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("products");
        }
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void getById_hitsRepositoryOnlyOnce_acrossRepeatedCalls() {
        Product widget = new Product("Widget", "desc", "SKU-1", new BigDecimal("9.99"));
        when(productRepository.findById(1L)).thenReturn(Optional.of(widget));

        Product first = productService.getById(1L);
        Product second = productService.getById(1L);
        Product third = productService.getById(1L);

        assertThat(first).isSameAs(second).isSameAs(third);
        verify(productRepository, times(1)).findById(1L);
    }

    /**
     * update() internally calls this.getById(id) to load the entity it mutates -
     * but that's a *self-invocation*: a call from one method to another on the
     * same object, which bypasses the @Cacheable proxy entirely (Spring AOP only
     * intercepts calls that arrive from outside the bean). So that internal call
     * always hits the repository directly, regardless of cache state - which
     * means the overall count here is 3, not the 2 a cache-oblivious reading
     * would suggest (first getById = cache miss = 1 hit; update()'s internal
     * self-invoked getById = always a hit = 1 more; final getById after eviction
     * = cache miss again = 1 more). This is arguably the right outcome for a
     * different reason too: update() needs the real Hibernate-managed entity to
     * mutate so JPA dirty-checking persists the change - a cached copy
     * deserialized from Redis would be a detached POJO, not a managed entity,
     * and mutating it would silently do nothing. The self-invocation gotcha
     * happens to save us from that bug here, even though that's not why it
     * exists.
     */
    @Test
    void update_alwaysReloadsFromRepositoryForMutation_dueToSelfInvocationBypassingTheCacheProxy() {
        Product widget = new Product("Widget", "desc", "SKU-1", new BigDecimal("9.99"));
        when(productRepository.findById(2L)).thenReturn(Optional.of(widget));

        productService.getById(2L);
        productService.update(2L, new ProductUpdateRequest("Widget v2", "desc", new BigDecimal("12.99")));
        productService.getById(2L);

        verify(productRepository, times(3)).findById(2L);
    }
}
