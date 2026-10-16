package com.aman.orderflow.messaging.event;

import java.math.BigDecimal;

public record PaymentFailedPayload(Long paymentId, Long orderId, BigDecimal amount, String reason) {
}
