package com.example.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** A customer order with a status field that moves through {@link OrderStatus}. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.NEW;

    @Column(name = "total", nullable = false)
    private BigDecimal total;

    protected Order() {
    }

    public Order(BigDecimal total) {
        this.total = total;
        this.status = OrderStatus.NEW;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markShipped() {
        this.status = OrderStatus.SHIPPED;
    }

    public void markDelivered() {
        this.status = OrderStatus.DELIVERED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotal() {
        return total;
    }
}
