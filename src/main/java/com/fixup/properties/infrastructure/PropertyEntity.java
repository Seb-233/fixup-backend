package com.fixup.properties.infrastructure;

import com.fixup.properties.domain.Property;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "properties")
class PropertyEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "area_m2", nullable = false, precision = 10, scale = 2)
    private BigDecimal areaM2;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PropertyEntity() {}

    PropertyEntity(Property property) {
        this.id = property.id();
        this.ownerUserId = property.ownerUserId();
        this.name = property.name();
        this.address = property.address();
        this.city = property.city();
        this.areaM2 = property.areaM2();
        this.createdAt = property.createdAt();
        this.updatedAt = property.updatedAt();
    }

    Property toDomain() {
        return new Property(id, ownerUserId, name, address, city, areaM2, createdAt, updatedAt);
    }
}
