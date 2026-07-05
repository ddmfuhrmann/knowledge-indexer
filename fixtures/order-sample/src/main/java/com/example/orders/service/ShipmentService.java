package com.example.orders.service;

import com.example.orders.domain.Order;
import com.example.orders.domain.Shipment;
import com.example.orders.event.OrderPaidEvent;
import com.example.orders.repository.OrderRepository;
import com.example.orders.repository.ShipmentRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** Drives a shipment through its lifecycle. Fulfilment starts when an order is paid. */
@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;

    public ShipmentService(ShipmentRepository shipmentRepository, OrderRepository orderRepository) {
        this.shipmentRepository = shipmentRepository;
        this.orderRepository = orderRepository;
    }

    /** Payment confirmed → open a shipment in PENDING. This is the event entry point. */
    @EventListener
    public Shipment onOrderPaid(OrderPaidEvent event) {
        Order order = orderRepository.findById(event.getOrderId()).orElseThrow();
        Shipment shipment = new Shipment(order);
        return shipmentRepository.save(shipment);
    }

    public Shipment dispatch(Long id, String carrier) {
        Shipment shipment = load(id);
        shipment.dispatch(carrier);
        return shipmentRepository.save(shipment);
    }

    public Shipment markInTransit(Long id) {
        Shipment shipment = load(id);
        shipment.markInTransit();
        return shipmentRepository.save(shipment);
    }

    public Shipment deliver(Long id) {
        Shipment shipment = load(id);
        shipment.markDelivered();
        return shipmentRepository.save(shipment);
    }

    public Shipment ret(Long id) {
        Shipment shipment = load(id);
        shipment.markReturned();
        return shipmentRepository.save(shipment);
    }

    public Shipment find(Long id) {
        return load(id);
    }

    private Shipment load(Long id) {
        return shipmentRepository.findById(id).orElseThrow();
    }
}
