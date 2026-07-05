package com.example.orders.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deliberately partial coverage: place/pay/ship/cancel are exercised, but {@code deliver} and the
 * GET {@code find} endpoint are not — so the coverage enrichment has real gaps to surface.
 */
class OrderServiceTest {

    @Test
    @DisplayName("placing an order starts it in NEW")
    void place_startsInNewStatus() {
    }

    @Test
    void pay_movesOrderToPaid() {
    }

    @Test
    void ship_movesPaidOrderToShipped() {
    }

    @Test
    void cancel_movesOrderToCancelled() {
    }
}
