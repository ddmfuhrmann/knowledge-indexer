package com.example.orders.web;

import com.example.orders.domain.Shipment;
import com.example.orders.service.ShipmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP surface for the shipment lifecycle. Each write delegates straight to the service. */
@RestController
@RequestMapping("/shipments")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping("/{id}/dispatch")
    public Shipment dispatch(@PathVariable Long id, @RequestParam String carrier) {
        return shipmentService.dispatch(id, carrier);
    }

    @PostMapping("/{id}/in-transit")
    public Shipment markInTransit(@PathVariable Long id) {
        return shipmentService.markInTransit(id);
    }

    @PostMapping("/{id}/deliver")
    public Shipment deliver(@PathVariable Long id) {
        return shipmentService.deliver(id);
    }

    @PostMapping("/{id}/return")
    public Shipment ret(@PathVariable Long id) {
        return shipmentService.ret(id);
    }

    @GetMapping("/{id}")
    public Shipment find(@PathVariable Long id) {
        return shipmentService.find(id);
    }
}
