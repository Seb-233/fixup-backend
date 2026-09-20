package com.fixup.properties.domain;

import com.fixup.properties.api.PropertyConflictException;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public record Property(
        UUID id,
        UUID ownerUserId,
        UUID managerUserId,
        PropertyType type,
        PropertyStatus status,
        String title,
        String description,
        String addressStreet,
        String addressNumber,
        String addressFloor,
        String addressApartment,
        String city,
        String zone,
        String postalCode,
        BigDecimal latitude,
        BigDecimal longitude,
        Double surfaceM2,
        Double coveredSurfaceM2,
        Integer bedrooms,
        Integer bathrooms,
        Integer coveredParkingSpots,
        Boolean hasBalcony,
        Boolean hasTerrace,
        Boolean hasGarden,
        Boolean hasElevator,
        Boolean hasPool,
        Boolean hasSecurity,
        Boolean petsAllowed,
        Boolean furnished,
        List<String> amenities,
        BigDecimal monthlyRentSuggestion,
        BigDecimal monthlyCondoFee,
        List<UUID> mediaIds,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant unlistedAt,
        Instant deletedAt) {

    public static final int MAX_TITLE = 150;
    public static final int MAX_DESCRIPTION = 4000;
    public static final int MAX_ADDRESS = 200;
    public static final int MAX_ZONE = 100;
    public static final int MAX_MEDIA = 30;
    public static final int MAX_AMENITIES = 50;

    public Property {
        if (title == null || title.isBlank())
            throw new PropertyConflictException("INVALID_TITLE", "Property title is required");
        if (title.length() > MAX_TITLE)
            throw new PropertyConflictException("INVALID_TITLE", "Title too long (max " + MAX_TITLE + ")");
        if (description != null && description.length() > MAX_DESCRIPTION)
            throw new PropertyConflictException("INVALID_DESCRIPTION", "Description too long");
        if (type == null)
            throw new PropertyConflictException("INVALID_TYPE", "Property type is required");
        if (city == null || city.isBlank() || city.length() > MAX_ADDRESS)
            throw new PropertyConflictException("INVALID_CITY", "Valid city is required");
        if (zone == null || zone.isBlank() || zone.length() > MAX_ZONE)
            throw new PropertyConflictException("INVALID_ZONE", "Valid zone is required");
        if (monthlyRentSuggestion != null && monthlyRentSuggestion.signum() < 0)
            throw new PropertyConflictException("INVALID_RENT", "Rent cannot be negative");
        if (monthlyCondoFee != null && monthlyCondoFee.signum() < 0)
            throw new PropertyConflictException("INVALID_CONDO", "Condo fee cannot be negative");
        if (surfaceM2 != null && surfaceM2 <= 0)
            throw new PropertyConflictException("INVALID_SURFACE", "Surface must be positive");
        if (coveredSurfaceM2 != null && coveredSurfaceM2 < 0)
            throw new PropertyConflictException("INVALID_COVERED_SURFACE", "Covered surface cannot be negative");
        if (bedrooms != null && bedrooms < 0)
            throw new PropertyConflictException("INVALID_BEDROOMS", "Bedrooms cannot be negative");
        if (bathrooms != null && bathrooms < 0)
            throw new PropertyConflictException("INVALID_BATHROOMS", "Bathrooms cannot be negative");
        if (coveredParkingSpots != null && coveredParkingSpots < 0)
            throw new PropertyConflictException("INVALID_PARKING", "Parking spots cannot be negative");
        if (mediaIds == null) mediaIds = List.of();
        if (mediaIds.size() > MAX_MEDIA)
            throw new PropertyConflictException("TOO_MANY_MEDIA", "Max " + MAX_MEDIA + " media attachments");
        if (amenities == null) amenities = List.of();
        amenities = sanitizeList(amenities);
        if (amenities.size() > MAX_AMENITIES)
            throw new PropertyConflictException("TOO_MANY_AMENITIES", "Max " + MAX_AMENITIES + " amenities");
        status = status == null ? PropertyStatus.DRAFT : status;
    }

    private static List<String> sanitizeList(List<String> raw) {
        List<String> sanitized = new ArrayList<>(raw.size());
        for (var item : raw) {
            if (item != null) {
                var s = item.trim();
                if (!s.isEmpty() && s.length() <= 80) sanitized.add(s);
            }
        }
        return Collections.unmodifiableList(sanitized);
    }

    public static Property create(UUID id, UUID ownerUserId, UUID managerUserId,
            PropertyType type, String title, String description,
            String addressStreet, String addressNumber, String addressFloor, String addressApartment,
            String city, String zone, String postalCode,
            BigDecimal latitude, BigDecimal longitude,
            Double surfaceM2, Double coveredSurfaceM2,
            Integer bedrooms, Integer bathrooms, Integer coveredParkingSpots,
            Boolean hasBalcony, Boolean hasTerrace, Boolean hasGarden,
            Boolean hasElevator, Boolean hasPool, Boolean hasSecurity,
            Boolean petsAllowed, Boolean furnished,
            List<String> amenities, BigDecimal monthlyRentSuggestion, BigDecimal monthlyCondoFee,
            List<UUID> mediaIds, Instant now) {
        return new Property(id, ownerUserId, managerUserId, type, PropertyStatus.DRAFT,
                title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                mediaIds, now, now, null, null, null);
    }

    public Property publish(Instant now) {
        if (status == PropertyStatus.DELETED)
            throw new PropertyConflictException("DELETED_PROPERTY", "Cannot publish a deleted property");
        if (monthlyRentSuggestion == null || monthlyRentSuggestion.signum() <= 0)
            throw new PropertyConflictException("CANNOT_PUBLISH", "Monthly rent is required to publish");
        return new Property(id, ownerUserId, managerUserId, type, PropertyStatus.PUBLISHED,
                title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                mediaIds, createdAt, now, now == null ? publishedAt : publishedAt == null ? now : publishedAt,
                null, deletedAt);
    }

    public Property unlist(Instant now) {
        if (status != PropertyStatus.PUBLISHED)
            throw new PropertyConflictException("NOT_PUBLISHED", "Only published properties can be unlisted");
        return new Property(id, ownerUserId, managerUserId, type, PropertyStatus.UNLISTED,
                title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                mediaIds, createdAt, now, publishedAt, now, deletedAt);
    }

    public Property relist(Instant now) {
        if (status != PropertyStatus.UNLISTED)
            throw new PropertyConflictException("NOT_UNLISTED", "Only unlisted properties can be relisted");
        return new Property(id, ownerUserId, managerUserId, type, PropertyStatus.PUBLISHED,
                title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                mediaIds, createdAt, now, now, null, deletedAt);
    }

    public Property delete(Instant now) {
        if (status == PropertyStatus.DELETED)
            throw new PropertyConflictException("ALREADY_DELETED", "Property is already deleted");
        return new Property(id, ownerUserId, managerUserId, type, PropertyStatus.DELETED,
                title, description,
                addressStreet, addressNumber, addressFloor, addressApartment,
                city, zone, postalCode, latitude, longitude,
                surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                mediaIds, createdAt, now, publishedAt, unlistedAt, now);
    }

    public Property update(UpdatePatch patch, Instant now) {
        if (status == PropertyStatus.DELETED)
            throw new PropertyConflictException("DELETED_PROPERTY", "Cannot update a deleted property");
        return new Property(id, ownerUserId, managerUserId,
                patch.type() != null ? patch.type() : type, status,
                patch.title() != null ? patch.title() : title,
                patch.description() != null ? patch.description() : description,
                patch.addressStreet() != null ? patch.addressStreet() : addressStreet,
                patch.addressNumber() != null ? patch.addressNumber() : addressNumber,
                patch.addressFloor() != null ? patch.addressFloor() : addressFloor,
                patch.addressApartment() != null ? patch.addressApartment() : addressApartment,
                patch.city() != null ? patch.city() : city,
                patch.zone() != null ? patch.zone() : zone,
                patch.postalCode() != null ? patch.postalCode() : postalCode,
                patch.latitude() != null ? patch.latitude() : latitude,
                patch.longitude() != null ? patch.longitude() : longitude,
                patch.surfaceM2() != null ? patch.surfaceM2() : surfaceM2,
                patch.coveredSurfaceM2() != null ? patch.coveredSurfaceM2() : coveredSurfaceM2,
                patch.bedrooms() != null ? patch.bedrooms() : bedrooms,
                patch.bathrooms() != null ? patch.bathrooms() : bathrooms,
                patch.coveredParkingSpots() != null ? patch.coveredParkingSpots() : coveredParkingSpots,
                patch.hasBalcony() != null ? patch.hasBalcony() : hasBalcony,
                patch.hasTerrace() != null ? patch.hasTerrace() : hasTerrace,
                patch.hasGarden() != null ? patch.hasGarden() : hasGarden,
                patch.hasElevator() != null ? patch.hasElevator() : hasElevator,
                patch.hasPool() != null ? patch.hasPool() : hasPool,
                patch.hasSecurity() != null ? patch.hasSecurity() : hasSecurity,
                patch.petsAllowed() != null ? patch.petsAllowed() : petsAllowed,
                patch.furnished() != null ? patch.furnished() : furnished,
                patch.amenities() != null ? patch.amenities() : amenities,
                patch.monthlyRentSuggestion() != null ? patch.monthlyRentSuggestion() : monthlyRentSuggestion,
                patch.monthlyCondoFee() != null ? patch.monthlyCondoFee() : monthlyCondoFee,
                patch.mediaIds() != null ? patch.mediaIds() : mediaIds,
                createdAt, now, publishedAt, unlistedAt, deletedAt);
    }

    public record UpdatePatch(
            PropertyType type,
            String title,
            String description,
            String addressStreet,
            String addressNumber,
            String addressFloor,
            String addressApartment,
            String city,
            String zone,
            String postalCode,
            BigDecimal latitude,
            BigDecimal longitude,
            Double surfaceM2,
            Double coveredSurfaceM2,
            Integer bedrooms,
            Integer bathrooms,
            Integer coveredParkingSpots,
            Boolean hasBalcony,
            Boolean hasTerrace,
            Boolean hasGarden,
            Boolean hasElevator,
            Boolean hasPool,
            Boolean hasSecurity,
            Boolean petsAllowed,
            Boolean furnished,
            List<String> amenities,
            BigDecimal monthlyRentSuggestion,
            BigDecimal monthlyCondoFee,
            List<UUID> mediaIds) {
    }

    public boolean isOwnedOrManagedBy(UUID userId) {
        if (userId == null) return false;
        if (ownerUserId != null && ownerUserId.equals(userId)) return true;
        return managerUserId != null && managerUserId.equals(userId);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private UUID ownerUserId;
        private UUID managerUserId;
        private PropertyType type;
        private String title;
        private String description;
        private String addressStreet;
        private String addressNumber;
        private String addressFloor;
        private String addressApartment;
        private String city;
        private String zone;
        private String postalCode;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private Double surfaceM2;
        private Double coveredSurfaceM2;
        private Integer bedrooms;
        private Integer bathrooms;
        private Integer coveredParkingSpots;
        private Boolean hasBalcony;
        private Boolean hasTerrace;
        private Boolean hasGarden;
        private Boolean hasElevator;
        private Boolean hasPool;
        private Boolean hasSecurity;
        private Boolean petsAllowed;
        private Boolean furnished;
        private List<String> amenities = List.of();
        private BigDecimal monthlyRentSuggestion;
        private BigDecimal monthlyCondoFee;
        private List<UUID> mediaIds = List.of();
        private Instant createdAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder ownerUserId(UUID v) { this.ownerUserId = v; return this; }
        public Builder managerUserId(UUID v) { this.managerUserId = v; return this; }
        public Builder type(PropertyType t) { this.type = t; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder addressStreet(String v) { this.addressStreet = v; return this; }
        public Builder addressNumber(String v) { this.addressNumber = v; return this; }
        public Builder addressFloor(String v) { this.addressFloor = v; return this; }
        public Builder addressApartment(String v) { this.addressApartment = v; return this; }
        public Builder city(String v) { this.city = v; return this; }
        public Builder zone(String v) { this.zone = v; return this; }
        public Builder postalCode(String v) { this.postalCode = v; return this; }
        public Builder latitude(BigDecimal v) { this.latitude = v; return this; }
        public Builder longitude(BigDecimal v) { this.longitude = v; return this; }
        public Builder surfaceM2(Double v) { this.surfaceM2 = v; return this; }
        public Builder coveredSurfaceM2(Double v) { this.coveredSurfaceM2 = v; return this; }
        public Builder bedrooms(Integer v) { this.bedrooms = v; return this; }
        public Builder bathrooms(Integer v) { this.bathrooms = v; return this; }
        public Builder coveredParkingSpots(Integer v) { this.coveredParkingSpots = v; return this; }
        public Builder hasBalcony(Boolean v) { this.hasBalcony = v; return this; }
        public Builder hasTerrace(Boolean v) { this.hasTerrace = v; return this; }
        public Builder hasGarden(Boolean v) { this.hasGarden = v; return this; }
        public Builder hasElevator(Boolean v) { this.hasElevator = v; return this; }
        public Builder hasPool(Boolean v) { this.hasPool = v; return this; }
        public Builder hasSecurity(Boolean v) { this.hasSecurity = v; return this; }
        public Builder petsAllowed(Boolean v) { this.petsAllowed = v; return this; }
        public Builder furnished(Boolean v) { this.furnished = v; return this; }
        public Builder amenities(List<String> v) { this.amenities = v; return this; }
        public Builder monthlyRentSuggestion(BigDecimal v) { this.monthlyRentSuggestion = v; return this; }
        public Builder monthlyCondoFee(BigDecimal v) { this.monthlyCondoFee = v; return this; }
        public Builder mediaIds(List<UUID> v) { this.mediaIds = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }

        public Property build() {
            var now = this.createdAt == null ? NOW_FALLBACK : this.createdAt;
            return Property.create(id, ownerUserId, managerUserId,
                    type, title, description,
                    addressStreet, addressNumber, addressFloor, addressApartment,
                    city, zone, postalCode, latitude, longitude,
                    surfaceM2, coveredSurfaceM2, bedrooms, bathrooms, coveredParkingSpots,
                    hasBalcony, hasTerrace, hasGarden, hasElevator, hasPool, hasSecurity,
                    petsAllowed, furnished, amenities, monthlyRentSuggestion, monthlyCondoFee,
                    mediaIds, now);
        }

        private static final Instant NOW_FALLBACK = Instant.parse("2025-09-19T12:00:00Z");
    }
}
