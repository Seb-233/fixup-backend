package com.fixup.properties.domain;

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
    private final Instant createdAt;
    private Instant updatedAt;

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.city = requireNonBlank(city, "city");
        this.areaM2 = requirePositive(areaM2, "areaM2");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2) {
        Instant now = Instant.now();
        return new Property(UUID.randomUUID(), ownerUserId, name, address, city, areaM2, now, now);
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
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
