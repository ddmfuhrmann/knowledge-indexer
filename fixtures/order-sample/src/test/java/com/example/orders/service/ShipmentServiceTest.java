package com.example.orders.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deliberately partial coverage: the paid → shipment-created event flow and {@code dispatch} are
 * exercised, but {@code markInTransit}, {@code deliver} and {@code ret} (return) are not — so the
 * coverage enrichment has real gaps to surface on the shipment lifecycle too.
 */
class ShipmentServiceTest {

    @Test
    @DisplayName("paying an order opens a shipment in PENDING")
    void onOrderPaid_opensPendingShipment() {
    }

    @Test
    void dispatch_movesShipmentToDispatched() {
    }
}
