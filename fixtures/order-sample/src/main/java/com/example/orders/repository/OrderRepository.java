package com.example.orders.repository;

import com.example.orders.domain.Order;

import java.util.Optional;

/**
 * Minimal repository abstraction (no Spring Data on the fixture classpath — the tool only parses
 * sources). Concrete enough for the call graph to resolve service → repository edges.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long id);
}
