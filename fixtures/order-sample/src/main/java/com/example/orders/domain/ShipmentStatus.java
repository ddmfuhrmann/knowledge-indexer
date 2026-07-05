package com.example.orders.domain;

/** Lifecycle status of a {@link Shipment}. Named "…Status" so the extractor treats it as a machine. */
public enum ShipmentStatus {
    PENDING,
    DISPATCHED,
    IN_TRANSIT,
    DELIVERED,
    RETURNED
}
