package com.fixup.properties.infrastructure;

import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.domain.Property;
import com.fixup.properties.domain.PropertyListing;
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

    @Column(name = "latitude", precision = 10, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 6)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PropertyStatus status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by_user_id")
    private UUID publishedByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", length = 16)
    private ListingType listingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", length = 32)
    private PropertyType propertyType;

    @Column(name = "price")
    private Long price;

    @Column(name = "admin_fee")
    private Long adminFee;

    @Column(name = "bedrooms")
    private Integer bedrooms;

    @Column(name = "bathrooms")
    private Integer bathrooms;

    @Column(name = "parking_spots")
    private Integer parkingSpots;

    @Column(name = "stratum")
    private Integer stratum;

    @Column(name = "neighborhood", length = 120)
    private String neighborhood;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "manager_user_id")
    private UUID managerUserId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "property_photos", joinColumns = @JoinColumn(name = "property_id"))
    @OrderColumn(name = "photo_order")
    @Column(name = "media_id", nullable = false)
    private List<UUID> photoMediaIds = new ArrayList<>();

    protected PropertyEntity() {}

    PropertyEntity(Property property) {
        this.id = property.id();
        this.ownerUserId = property.ownerUserId();
        this.createdAt = property.createdAt();
        apply(property);
    }

    void apply(Property property) {
        this.name = property.name();
        this.address = property.address();
        this.city = property.city();
        this.areaM2 = property.areaM2();
        this.latitude = property.latitude();
        this.longitude = property.longitude();
        this.status = property.status();
        this.publishedAt = property.publishedAt();
        this.publishedByUserId = property.publishedByUserId();
        this.updatedAt = property.updatedAt();
        var listing = property.listing();
        this.listingType = listing.listingType();
        this.propertyType = listing.propertyType();
        this.price = listing.price();
        this.adminFee = listing.adminFee();
        this.bedrooms = listing.bedrooms();
        this.bathrooms = listing.bathrooms();
        this.parkingSpots = listing.parkingSpots();
        this.stratum = listing.stratum();
        this.neighborhood = listing.neighborhood();
        this.description = listing.description();
        this.managerUserId = property.managerUserId();
        this.photoMediaIds = new ArrayList<>(property.photoMediaIds());
    }

    Property toDomain() {
        return new Property(
            id, ownerUserId, name, address, city, areaM2,
            latitude, longitude,
            status, publishedAt, publishedByUserId,
            createdAt, updatedAt,
            new PropertyListing(listingType, propertyType, price, adminFee, bedrooms, bathrooms, parkingSpots,
                    stratum, neighborhood, description),
            managerUserId, List.copyOf(photoMediaIds)
        );
    }

    UUID id() {
        return id;
    }
}
