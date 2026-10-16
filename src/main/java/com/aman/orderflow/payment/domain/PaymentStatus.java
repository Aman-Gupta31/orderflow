package com.aman.orderflow.payment.domain;

/**
 * No PENDING state: real payment gateways are asynchronous and need one (a
 * webhook or poll confirms the outcome later), but this mock resolves
 * synchronously within the request - a deliberate simplification, not an
 * oversight. See docs/learning-guide/07-payments-and-kafka.md.
 */
public enum PaymentStatus {
    SUCCEEDED,
    FAILED
}
