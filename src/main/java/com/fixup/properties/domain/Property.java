package com.fixup.properties.domain;

import com.fixup.properties.api.PropertyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Property {
    public static final int MAX_PHOTOS = 12;

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
    private PropertyListing listing;
    private UUID managerUserId;
    private List<UUID> photoMediaIds;

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                    BigDecimal latitude, BigDecimal longitude,
                    PropertyStatus status, Instant publishedAt, UUID publishedByUserId,
                    Instant createdAt, Instant updatedAt) {
        this(id, ownerUserId, name, address, city, areaM2, latitude, longitude, status, publishedAt,
                publishedByUserId, createdAt, updatedAt, PropertyListing.EMPTY, null, List.of());
    }

    public Property(UUID id, UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                    BigDecimal latitude, BigDecimal longitude,
                    PropertyStatus status, Instant publishedAt, UUID publishedByUserId,
                    Instant createdAt, Instant updatedAt,
                    PropertyListing listing, UUID managerUserId, List<UUID> photoMediaIds) {
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
        this.listing = listing == null ? PropertyListing.EMPTY : listing;
        this.managerUserId = managerUserId;
        this.photoMediaIds = requirePhotos(photoMediaIds);
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2) {
        Instant now = Instant.now();
        return new Property(
            UUID.randomUUID(), ownerUserId, name, address, city, areaM2,
            null, null,
            PropertyStatus.PUBLISHED, now, ownerUserId,
            now, now
        );
    }

    public static Property create(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                                  BigDecimal latitude, BigDecimal longitude) {
        requireCoordinate(latitude, "latitude", "-90", "90");
        requireCoordinate(longitude, "longitude", "-180", "180");
        Instant now = Instant.now();
        return new Property(
            UUID.randomUUID(), ownerUserId, name, address, city, areaM2,
            latitude, longitude,
            PropertyStatus.PUBLISHED, now, ownerUserId,
            now, now
        );
    }

    /**
     * FR-UC-02: registra el inmueble con su aviso. Publicar es gratuito e inmediato; quien prefiera
     * revisarlo antes lo deja en borrador y lo publica después.
     */
    public static Property register(UUID ownerUserId, String name, String address, String city, BigDecimal areaM2,
                                    BigDecimal latitude, BigDecimal longitude, PropertyListing listing,
                                    boolean publish, UUID managerUserId) {
        if (latitude != null || longitude != null) {
            requireCoordinate(latitude, "latitude", "-90", "90");
            requireCoordinate(longitude, "longitude", "-180", "180");
        }
        Instant now = Instant.now();
        return new Property(UUID.randomUUID(), ownerUserId, name, address, city, areaM2, latitude, longitude,
                publish ? PropertyStatus.PUBLISHED : PropertyStatus.DRAFT,
                publish ? now : null, publish ? ownerUserId : null, now, now, listing, managerUserId, List.of());
    }

    /** Un inmueble en borrador o retirado vuelve al catálogo; uno ya publicado no cambia. */
    public boolean publish(UUID actorUserId, Instant now) {
        if (status == PropertyStatus.PUBLISHED) {
            return false;
        }
        status = PropertyStatus.PUBLISHED;
        publishedAt = now;
        publishedByUserId = Objects.requireNonNull(actorUserId, "actorUserId");
        updatedAt = now;
        return true;
    }

    /** Retirar del catálogo conserva el historial: el inmueble queda ARCHIVED, no se borra. */
    public void unpublish(Instant now) {
        if (status != PropertyStatus.PUBLISHED) {
            throw new IllegalStateException("Only a published property can be withdrawn from the catalog");
        }
        status = PropertyStatus.ARCHIVED;
        updatedAt = now;
    }

    public void updateDetails(String name, String address, String city, BigDecimal areaM2,
                              BigDecimal latitude, BigDecimal longitude, PropertyListing listing, Instant now) {
        if (latitude != null || longitude != null) {
            requireCoordinate(latitude, "latitude", "-90", "90");
            requireCoordinate(longitude, "longitude", "-180", "180");
        }
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.city = requireNonBlank(city, "city");
        this.areaM2 = requirePositive(areaM2, "areaM2");
        this.latitude = latitude;
        this.longitude = longitude;
        this.listing = listing == null ? PropertyListing.EMPTY : listing;
        this.updatedAt = now;
    }

    public void replacePhotos(List<UUID> mediaIds, Instant now) {
        this.photoMediaIds = requirePhotos(mediaIds);
        this.updatedAt = now;
    }

    public void assignManager(UUID managerUserId, Instant now) {
        if (managerUserId != null && managerUserId.equals(ownerUserId)) {
            throw new IllegalArgumentException("The owner cannot be the manager of their own property");
        }
        this.managerUserId = managerUserId;
        this.updatedAt = now;
    }

    /** FR-UC-11: el propietario y el administrador que lo gestiona deciden sobre el inmueble. */
    public boolean isControlledBy(UUID userId) {
        return ownerUserId.equals(userId) || (managerUserId != null && managerUserId.equals(userId));
    }

    private static List<UUID> requirePhotos(List<UUID> mediaIds) {
        if (mediaIds == null) {
            return List.of();
        }
        if (mediaIds.size() > MAX_PHOTOS) {
            throw new IllegalArgumentException("A property accepts at most " + MAX_PHOTOS + " photos");
        }
        if (mediaIds.stream().anyMatch(Objects::isNull) || new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("Photo identifiers must be distinct and not null");
        }
        return List.copyOf(mediaIds);
    }

    private static void requireCoordinate(BigDecimal value, String fieldName, String minimum, String maximum) {
        var min = new BigDecimal(minimum);
        var max = new BigDecimal(maximum);
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum);
        }
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
    public PropertyListing listing() { return listing; }
    public UUID managerUserId() { return managerUserId; }
    public List<UUID> photoMediaIds() { return photoMediaIds; }
}
