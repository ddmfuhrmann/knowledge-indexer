package com.example.orders.event;

/**
 * Published when an order is paid. Consumed in-process to kick off fulfilment (a {@link
 * com.example.orders.domain.Shipment} is created). Named "…Event" so the extractor treats it as a
 * domain event even without an in-source consumer.
 */
public class OrderPaidEvent {

    private final Long orderId;

    public OrderPaidEvent(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
