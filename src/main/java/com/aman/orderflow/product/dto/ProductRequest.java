package com.aman.orderflow.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 64) String sku,
        @NotNull @DecimalMin(value = "0.01") BigDecimal price,
        @NotNull Integer initialQuantity
) {
}
