package com.example.orders.repository;

import com.example.orders.domain.Shipment;

import java.util.List;
import java.util.Optional;

/**
 * Minimal repository abstraction for {@link Shipment} (no Spring Data on the fixture classpath — the
 * tool only parses sources). Concrete enough for the call graph to resolve service → repository edges.
 */
public interface ShipmentRepository {

    Shipment save(Shipment shipment);

    Optional<Shipment> findById(Long id);

    List<Shipment> findByOrderId(Long orderId);
}
