package com.aman.orderflow.payment.dto;

import com.aman.orderflow.payment.domain.Payment;
import com.aman.orderflow.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        PaymentStatus status,
        BigDecimal amount,
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getStatus(),
                payment.getAmount(), payment.getCreatedAt());
    }
}
