package com.fixup.testsupport;

import java.math.BigDecimal;
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

    public static void insertWithLocation(
            JdbcTemplate jdbc,
            UUID propertyId,
            UUID ownerId,
            String name,
            String status,
            BigDecimal latitude,
            BigDecimal longitude) {
        jdbc.update("""
                INSERT INTO properties (id, owner_user_id, name, address, city, area_m2,
                    latitude, longitude, status, published_at, published_by_user_id, created_at, updated_at)
                VALUES (?, ?, ?, 'Search address', 'Bogota', 68.50, ?, ?, ?,
                    CASE WHEN ? = 'DRAFT' THEN NULL ELSE CURRENT_TIMESTAMP END,
                    CASE WHEN ? = 'DRAFT' THEN NULL ELSE ? END,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, propertyId, ownerId, name, latitude, longitude, status,
                status, status, ownerId);
    }
}
