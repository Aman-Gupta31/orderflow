package com.aman.orderflow.inventory;

import com.aman.orderflow.common.exception.InsufficientStockException;
import com.aman.orderflow.inventory.domain.Inventory;
import com.aman.orderflow.inventory.repository.InventoryRepository;
import com.aman.orderflow.inventory.service.InventoryService;
import com.aman.orderflow.product.domain.Product;
import com.aman.orderflow.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the atomic conditional UPDATE in InventoryRepository.reserve prevents
 * overselling when two "customers" race for the last unit of stock. This requires a
 * real MySQL instance (via Testcontainers) because an in-memory/H2 substitute would
 * not exercise the actual row-level locking MySQL performs during the UPDATE, and the
 * scenario this test defends against is specifically a multi-instance/concurrent-request
 * race on a real database.
 *
 * Runs via `./mvnw verify` (failsafe), not the default `./mvnw test` (surefire), and
 * requires Docker to be running locally. If Docker is unavailable this test is skipped
 * at the environment level (Testcontainers throws before any assertion runs) rather than
 * weakened or deleted - see docs/TESTING.md for the current status on this machine.
 */
@Testcontainers
@SpringBootTest
class ConcurrentStockReservationIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("orderflow")
            .withUsername("orderflow")
            .withPassword("orderflow");

    @DynamicPropertySource
    static void registerMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryService inventoryService;

    @Test
    void onlyOneOfTwoConcurrentBuyers_canReserveTheLastUnit() throws InterruptedException {
        Product product = productRepository.save(
                new Product("Last Unit Widget", "desc", "SKU-LAST-UNIT", new BigDecimal("9.99")));
        inventoryRepository.save(new Inventory(product.getId(), 1));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        List<Runnable> buyers = List.of(
                () -> attemptReserve(product.getId(), startGate, successes, failures),
                () -> attemptReserve(product.getId(), startGate, successes, failures)
        );
        buyers.forEach(pool::execute);

        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);

        Inventory finalState = inventoryRepository.findByProductId(product.getId()).orElseThrow();
        assertThat(finalState.getQuantityAvailable()).isZero();
        assertThat(finalState.getQuantityReserved()).isEqualTo(1);
    }

    private void attemptReserve(Long productId, CountDownLatch startGate,
                                 AtomicInteger successes, AtomicInteger failures) {
        try {
            startGate.await();
            inventoryService.reserve(productId, 1);
            successes.incrementAndGet();
        } catch (InsufficientStockException e) {
            failures.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
