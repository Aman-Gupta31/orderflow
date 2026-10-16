package com.aman.orderflow.messaging.event;

import java.math.BigDecimal;

public record OrderCreatedPayload(Long orderId, Long userId, String customerEmail, BigDecimal totalAmount) {
}
