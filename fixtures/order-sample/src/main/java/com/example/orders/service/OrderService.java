package com.example.orders.service;

import com.example.orders.domain.Order;
import com.example.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** Application service driving an order through its lifecycle. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order place(BigDecimal total) {
        Order order = new Order(total);
        return orderRepository.save(order);
    }

    public Order pay(Long id) {
        Order order = load(id);
        order.markPaid();
        return orderRepository.save(order);
    }

    public Order ship(Long id) {
        Order order = load(id);
        order.markShipped();
        return orderRepository.save(order);
    }

    public Order deliver(Long id) {
        Order order = load(id);
        order.markDelivered();
        return orderRepository.save(order);
    }

    public Order cancel(Long id) {
        Order order = load(id);
        order.cancel();
        return orderRepository.save(order);
    }

    public Order find(Long id) {
        return load(id);
    }

    private Order load(Long id) {
        return orderRepository.findById(id).orElseThrow();
    }
}
