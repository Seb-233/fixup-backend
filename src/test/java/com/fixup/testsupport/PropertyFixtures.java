package com.fixup.testsupport;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared persisted fixture that respects the PUBLISHED domain invariant. */
public final class PropertyFixtures {
    private PropertyFixtures() {}

    public static void insertPublished(JdbcTemplate jdbc, UUID propertyId, UUID ownerId) {
        jdbc.update("""
                INSERT INTO properties (id, owner_user_id, name, address, city, area_m2,
                    status, published_at, published_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'Prop', 'Addr', 'City', 10, 'PUBLISHED',
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, propertyId, ownerId, ownerId);
    }
}
