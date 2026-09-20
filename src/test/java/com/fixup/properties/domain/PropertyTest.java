package com.fixup.properties.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PropertyTest {

    @Test
    void createsValidProperty() {
        assertDoesNotThrow(() -> Property.create(
            UUID.randomUUID(), "My House", "123 Main St", "Metropolis", new BigDecimal("85.50")
        ));
    }

    @Test
    void requiresStrictlyPositiveArea() {
        assertThrows(IllegalArgumentException.class, () -> Property.create(
            UUID.randomUUID(), "House", "Address", "City", BigDecimal.ZERO
        ));
        assertThrows(IllegalArgumentException.class, () -> Property.create(
            UUID.randomUUID(), "House", "Address", "City", new BigDecimal("-5.0")
        ));
    }

    @Test
    void requiresNonBlankStrings() {
        assertThrows(IllegalArgumentException.class, () -> Property.create(
            UUID.randomUUID(), "  ", "Address", "City", new BigDecimal("10.0")
        ));
    }
}
