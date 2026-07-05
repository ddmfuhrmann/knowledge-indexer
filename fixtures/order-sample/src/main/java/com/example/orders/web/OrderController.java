package com.example.orders.web;

import com.example.orders.domain.Order;
import com.example.orders.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** HTTP surface for the order lifecycle. Each write delegates straight to the service. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Order place(BigDecimal total) {
        return orderService.place(total);
    }

    @PostMapping("/{id}/pay")
    public Order pay(@PathVariable Long id) {
        return orderService.pay(id);
    }

    @PostMapping("/{id}/ship")
    public Order ship(@PathVariable Long id) {
        return orderService.ship(id);
    }

    @PostMapping("/{id}/deliver")
    public Order deliver(@PathVariable Long id) {
        return orderService.deliver(id);
    }

    @PostMapping("/{id}/cancel")
    public Order cancel(@PathVariable Long id) {
        return orderService.cancel(id);
    }

    @GetMapping("/{id}")
    public Order find(@PathVariable Long id) {
        return orderService.find(id);
    }
}
