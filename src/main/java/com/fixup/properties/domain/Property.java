package com.fixup.properties.domain;

import com.fixup.properties.api.PropertyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Property {
    private final UUID id;
    private final UUID ownerUserId;
    private String name;
    private String address;
    private String city;
    private BigDecimal areaM2;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private PropertyStatus status;
    private Instant publishedAt;
    private UUID publishedByUserId;
    private final Instant createdAt;
    private Instant updatedAt;

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                    BigDecimal latitude, BigDecimal longitude,
                    PropertyStatus status, Instant publishedAt, UUID publishedByUserId,
                    Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.city = requireNonBlank(city, "city");
        this.areaM2 = requirePositive(areaM2, "areaM2");
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (status == PropertyStatus.PUBLISHED || status == PropertyStatus.ARCHIVED) {
            Objects.requireNonNull(publishedAt, "publishedAt must not be null when status is not DRAFT");
            Objects.requireNonNull(publishedByUserId, "publishedByUserId must not be null when status is not DRAFT");
        }
        this.publishedAt = publishedAt;
        this.publishedByUserId = publishedByUserId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2) {
        return create(ownerUserId, name, address, city, areaM2, null, null);
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                                  BigDecimal latitude, BigDecimal longitude) {
        Instant now = Instant.now();
        return new Property(
            UUID.randomUUID(), ownerUserId, name, address, city, areaM2,
            latitude, longitude,
            PropertyStatus.PUBLISHED, now, ownerUserId,
            now, now
        );
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be strictly positive");
        }
        return value;
    }

    public UUID id() { return id; }
    public UUID ownerUserId() { return ownerUserId; }
    public String name() { return name; }
    public String address() { return address; }
    public String city() { return city; }
    public BigDecimal areaM2() { return areaM2; }
    public BigDecimal latitude() { return latitude; }
    public BigDecimal longitude() { return longitude; }
    public PropertyStatus status() { return status; }
    public Instant publishedAt() { return publishedAt; }
    public UUID publishedByUserId() { return publishedByUserId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
