package com.aman.orderflow.order.domain;

import java.util.Set;

public enum OrderStatus {
    CREATED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final Set<OrderStatus> CANCELLABLE_FROM = Set.of(CREATED, CONFIRMED);

    public boolean canCancel() {
        return CANCELLABLE_FROM.contains(this);
    }
}
