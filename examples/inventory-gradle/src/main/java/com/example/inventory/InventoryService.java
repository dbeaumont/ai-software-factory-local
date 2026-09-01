package com.example.inventory;

import java.util.HashMap;
import java.util.Map;

public final class InventoryService {
    private final Map<String, Integer> quantities = new HashMap<>();

    public int available(String sku) {
        return quantities.getOrDefault(sku, 0);
    }

    public void setAvailable(String sku, int quantity) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity cannot be negative");
        }
        quantities.put(sku, quantity);
    }
}
