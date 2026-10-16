package com.aman.orderflow.order.dto;

import com.aman.orderflow.order.domain.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        int quantity,
        BigDecimal unitPriceAtPurchase,
        BigDecimal lineTotal
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getQuantity(),
                item.getUnitPriceAtPurchase(),
                item.lineTotal()
        );
    }
}
