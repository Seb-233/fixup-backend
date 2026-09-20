package com.fixup.properties.infrastructure;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "properties")
class PropertyEntity {
    @Id
    private UUID id;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Column(name = "manager_user_id")
    private UUID managerUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 20)
    private PropertyType type;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PropertyStatus status;
    @Column(nullable = false, length = 150)
    private String title;
    @Column(length = 4000)
    private String description;
    @Column(name = "address_street", length = 200)
    private String addressStreet;
    @Column(name = "address_number", length = 30)
    private String addressNumber;
    @Column(name = "address_floor", length = 20)
    private String addressFloor;
    @Column(name = "address_apartment", length = 20)
    private String addressApartment;
    @Column(nullable = false, length = 200)
    private String city;
    @Column(nullable = false, length = 100)
    private String zone;
    @Column(name = "postal_code", length = 20)
    private String postalCode;
    @Column(precision = 10, scale = 6)
    private BigDecimal latitude;
    @Column(precision = 10, scale = 6)
    private BigDecimal longitude;
    @Column(name = "surface_m2")
    private Double surfaceM2;
    @Column(name = "covered_surface_m2")
    private Double coveredSurfaceM2;
    private Integer bedrooms;
    private Integer bathrooms;
    @Column(name = "covered_parking_spots")
    private Integer coveredParkingSpots;
    @Column(name = "has_balcony")
    private Boolean hasBalcony;
    @Column(name = "has_terrace")
    private Boolean hasTerrace;
    @Column(name = "has_garden")
    private Boolean hasGarden;
    @Column(name = "has_elevator")
    private Boolean hasElevator;
    @Column(name = "has_pool")
    private Boolean hasPool;
    @Column(name = "has_security")
    private Boolean hasSecurity;
    @Column(name = "pets_allowed")
    private Boolean petsAllowed;
    private Boolean furnished;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "property_amenities", joinColumns = @JoinColumn(name = "property_id"))
    @Column(name = "amenity", length = 80)
    @OrderColumn(name = "pos")
    private List<String> amenities;
    @Column(name = "monthly_rent_suggestion", precision = 14, scale = 2)
    private BigDecimal monthlyRentSuggestion;
    @Column(name = "monthly_condo_fee", precision = 14, scale = 2)
    private BigDecimal monthlyCondoFee;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "property_media_ids", joinColumns = @JoinColumn(name = "property_id"))
    @Column(name = "media_id")
    @OrderColumn(name = "pos")
    private List<UUID> mediaIds;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "unlisted_at")
    private Instant unlistedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public PropertyEntity() {
    }

    static PropertyEntity from(com.fixup.properties.domain.Property p) {
        var e = new PropertyEntity();
        e.id = p.id();
        e.ownerUserId = p.ownerUserId();
        e.managerUserId = p.managerUserId();
        e.type = p.type();
        e.status = p.status();
        e.title = p.title();
        e.description = p.description();
        e.addressStreet = p.addressStreet();
        e.addressNumber = p.addressNumber();
        e.addressFloor = p.addressFloor();
        e.addressApartment = p.addressApartment();
        e.city = p.city();
        e.zone = p.zone();
        e.postalCode = p.postalCode();
        e.latitude = p.latitude();
        e.longitude = p.longitude();
        e.surfaceM2 = p.surfaceM2();
        e.coveredSurfaceM2 = p.coveredSurfaceM2();
        e.bedrooms = p.bedrooms();
        e.bathrooms = p.bathrooms();
        e.coveredParkingSpots = p.coveredParkingSpots();
        e.hasBalcony = p.hasBalcony();
        e.hasTerrace = p.hasTerrace();
        e.hasGarden = p.hasGarden();
        e.hasElevator = p.hasElevator();
        e.hasPool = p.hasPool();
        e.hasSecurity = p.hasSecurity();
        e.petsAllowed = p.petsAllowed();
        e.furnished = p.furnished();
        e.amenities = p.amenities() == null ? List.of() : new ArrayList<>(p.amenities());
        e.monthlyRentSuggestion = p.monthlyRentSuggestion();
        e.monthlyCondoFee = p.monthlyCondoFee();
        e.mediaIds = p.mediaIds() == null ? List.of() : new ArrayList<>(p.mediaIds());
        e.createdAt = p.createdAt();
        e.updatedAt = p.updatedAt();
        e.publishedAt = p.publishedAt();
        e.unlistedAt = p.unlistedAt();
        e.deletedAt = p.deletedAt();
        return e;
    }

    com.fixup.properties.domain.Property toDomain() {
        return new com.fixup.properties.domain.Property(
                id, ownerUserId, managerUserId, type, status, title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, List.copyOf(amenities == null ? List.of() : amenities),
                monthlyRentSuggestion, monthlyCondoFee,
                List.copyOf(mediaIds == null ? List.of() : mediaIds),
                createdAt, updatedAt, publishedAt, unlistedAt, deletedAt);
    }

    public UUID getId() { return id; }
    public Instant getPublishedAt() { return publishedAt; }
    public PropertyStatus getStatus() { return status; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public UUID getManagerUserId() { return managerUserId; }
    public PropertyType getType() { return type; }
    public String getTitle() { return title; }
    public String getCity() { return city; }
    public String getZone() { return zone; }
    public BigDecimal getMonthlyRentSuggestion() { return monthlyRentSuggestion; }
    public Integer getBedrooms() { return bedrooms; }
    public Integer getBathrooms() { return bathrooms; }
    public Double getSurfaceM2() { return surfaceM2; }
    public List<UUID> getMediaIds() { return mediaIds == null ? List.of() : mediaIds; }
    public Instant getCreatedAt() { return createdAt; }
}
