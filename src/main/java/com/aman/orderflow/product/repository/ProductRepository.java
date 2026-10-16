package com.aman.orderflow.product.repository;

import com.aman.orderflow.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> getBySku(String sku);

    boolean existsBySku(String sku);

    Page<Product> findByActiveTrueAndNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Product> findByActiveTrue(Pageable pageable);
}
