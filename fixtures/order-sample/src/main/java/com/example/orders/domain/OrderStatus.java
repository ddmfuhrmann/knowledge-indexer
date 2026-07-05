package com.example.orders.domain;

/** Lifecycle status of an {@link Order}. Named "…Status" so the extractor treats it as a machine. */
public enum OrderStatus {
    NEW,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
