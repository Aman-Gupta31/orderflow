package com.aman.orderflow.messaging.event;

public record OrderCancelledPayload(Long orderId, Long userId) {
}
