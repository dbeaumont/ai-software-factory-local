package com.example.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryServiceTest {
    @Test
    void storesAvailableQuantity() {
        InventoryService inventory = new InventoryService();
        inventory.setAvailable("SKU-1", 7);
        assertEquals(7, inventory.available("SKU-1"));
    }

    @Test
    void rejectsNegativeQuantity() {
        InventoryService inventory = new InventoryService();
        assertThrows(IllegalArgumentException.class, () -> inventory.setAvailable("SKU-1", -1));
    }
}
