package com.aman.orderflow.messaging.event;

import java.math.BigDecimal;

public record PaymentSucceededPayload(Long paymentId, Long orderId, BigDecimal amount) {
}
