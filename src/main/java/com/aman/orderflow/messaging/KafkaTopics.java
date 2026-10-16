package com.aman.orderflow.messaging;

public final class KafkaTopics {

    public static final String ORDER_EVENTS = "orderflow.order-events";
    public static final String PAYMENT_EVENTS = "orderflow.payment-events";

    public static final String ORDER_EVENTS_DLT = ORDER_EVENTS + ".dlt";
    public static final String PAYMENT_EVENTS_DLT = PAYMENT_EVENTS + ".dlt";

    private KafkaTopics() {
    }
}
