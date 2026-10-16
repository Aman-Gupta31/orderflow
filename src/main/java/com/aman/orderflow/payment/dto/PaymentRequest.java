package com.aman.orderflow.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * simulateFailure is a mock-only testing hook - it lets a caller deterministically
 * exercise the failure path without needing a real, nondeterministic payment
 * gateway to misbehave on cue. A real provider integration would have no such
 * field; the outcome would come from the provider's own response/webhook.
 */
public record PaymentRequest(
        @NotNull Long orderId,
        Boolean simulateFailure
) {
    public boolean shouldSimulateFailure() {
        return Boolean.TRUE.equals(simulateFailure);
    }
}
