package com.example.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** The physical delivery of an {@link Order}, moving through {@link ShipmentStatus}. */
@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "status", nullable = false, length = 20)
    private ShipmentStatus status = ShipmentStatus.PENDING;

    @Column(name = "carrier", length = 40)
    private String carrier;

    protected Shipment() {
    }

    public Shipment(Order order) {
        this.order = order;
        this.status = ShipmentStatus.PENDING;
    }

    public void dispatch(String carrier) {
        this.carrier = carrier;
        this.status = ShipmentStatus.DISPATCHED;
    }

    public void markInTransit() {
        this.status = ShipmentStatus.IN_TRANSIT;
    }

    public void markDelivered() {
        this.status = ShipmentStatus.DELIVERED;
    }

    public void markReturned() {
        this.status = ShipmentStatus.RETURNED;
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getCarrier() {
        return carrier;
    }
}
