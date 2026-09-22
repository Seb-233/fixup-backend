package com.fixup.properties.domain;

import com.fixup.properties.api.PropertyConflictException;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;

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

    // FR-UC-12: estado de publicación. Un inmueble nace como borrador y solo se ofrece cuando su
    // dueño lo publica; hasta entonces title, zone y monthlyRentSuggestion pueden estar vacíos.
    private PropertyType type;
    private PropertyStatus status;
    private String title;
    private String description;
    private String zone;
    private BigDecimal monthlyRentSuggestion;
    private Instant publishedAt;
    private Instant unlistedAt;

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2, Instant createdAt, Instant updatedAt) {
        this(id, ownerUserId, name, address, city, areaM2, createdAt, updatedAt,
            PropertyType.APARTMENT, PropertyStatus.DRAFT, null, null, null, null, null, null);
    }

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
            Instant createdAt, Instant updatedAt, PropertyType type, PropertyStatus status, String title,
            String description, String zone, BigDecimal monthlyRentSuggestion, Instant publishedAt, Instant unlistedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.city = requireNonBlank(city, "city");
        this.areaM2 = requirePositive(areaM2, "areaM2");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.type = type == null ? PropertyType.APARTMENT : type;
        this.status = status == null ? PropertyStatus.DRAFT : status;
        this.title = title;
        this.description = description;
        this.zone = zone;
        this.monthlyRentSuggestion = monthlyRentSuggestion;
        this.publishedAt = publishedAt;
        this.unlistedAt = unlistedAt;
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2) {
        Instant now = Instant.now();
        return new Property(UUID.randomUUID(), ownerUserId, name, address, city, areaM2, now, now);
    }

    /**
     * FR-UC-12: un inmueble se publica con los datos que la oferta muestra. El precio sugerido, el
     * título y la zona no son opcionales aquí aunque la columna los admita nula: una fila en DRAFT
     * puede no tenerlos todavía, una publicada no.
     */
    public void publish(PropertyType type, String title, String description, String zone,
            BigDecimal monthlyRentSuggestion, Instant now) {
        if (status == PropertyStatus.PUBLISHED) {
            throw new PropertyConflictException("ALREADY_PUBLISHED",
                "The property is already published");
        }
        this.type = type == null ? this.type : type;
        this.title = requireNonBlank(title, "title");
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.zone = requireNonBlank(zone, "zone");
        this.monthlyRentSuggestion = requirePositive(monthlyRentSuggestion, "monthlyRentSuggestion");
        this.status = PropertyStatus.PUBLISHED;
        this.publishedAt = this.publishedAt == null ? now : this.publishedAt;
        this.unlistedAt = null;
        this.updatedAt = now;
    }

    /** FR-UC-12: retirar la oferta no borra su historia; publishedAt se conserva. */
    public void unlist(Instant now) {
        if (status != PropertyStatus.PUBLISHED) {
            throw new PropertyConflictException("NOT_PUBLISHED",
                "Only a published property can be unlisted");
        }
        this.status = PropertyStatus.UNLISTED;
        this.unlistedAt = now;
        this.updatedAt = now;
    }

    public boolean isPublished() {
        return status == PropertyStatus.PUBLISHED;
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
    public PropertyType type() { return type; }
    public PropertyStatus status() { return status; }
    public String title() { return title; }
    public String description() { return description; }
    public String zone() { return zone; }
    public BigDecimal monthlyRentSuggestion() { return monthlyRentSuggestion; }
    public Instant publishedAt() { return publishedAt; }
    public Instant unlistedAt() { return unlistedAt; }
}
