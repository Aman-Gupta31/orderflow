package com.aman.orderflow.product.dto;

import com.aman.orderflow.product.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String name,
        String description,
        String sku,
        BigDecimal price,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                product.getSku(),
                product.getPrice(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
