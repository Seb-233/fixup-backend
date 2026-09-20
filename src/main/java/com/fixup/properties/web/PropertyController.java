package com.fixup.properties.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.media.api.SignedMediaView;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.application.DeleteProperty;
import com.fixup.properties.application.GetProperty;
import com.fixup.properties.application.ListMyProperties;
import com.fixup.properties.application.ListPublishedProperties;
import com.fixup.properties.application.PropertySummary;
import com.fixup.properties.application.PublishMultipleProperties;
import com.fixup.properties.application.PublishProperty;
import com.fixup.properties.application.RelistProperty;
import com.fixup.properties.application.UnlistProperty;
import com.fixup.properties.application.UpdateProperty;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/properties", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid fields, missing data or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or not authorized on this property",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Property not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Property state does not allow the requested transition or invalid data",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class PropertyController {
    private final CurrentActorProvider actors;
    private final PublishProperty publishProperty;
    private final PublishMultipleProperties publishMultiple;
    private final ListMyProperties listMy;
    private final ListPublishedProperties listPublished;
    private final GetProperty getProperty;
    private final UpdateProperty updateProperty;
    private final UnlistProperty unlistProperty;
    private final RelistProperty relistProperty;
    private final DeleteProperty deleteProperty;
    private final MediaAttachmentService mediaAttachmentService;

    PropertyController(CurrentActorProvider actors,
            PublishProperty publishProperty,
            PublishMultipleProperties publishMultiple,
            ListMyProperties listMy,
            ListPublishedProperties listPublished,
            GetProperty getProperty,
            UpdateProperty updateProperty,
            UnlistProperty unlistProperty,
            RelistProperty relistProperty,
            DeleteProperty deleteProperty,
            MediaAttachmentService mediaAttachmentService) {
        this.actors = actors;
        this.publishProperty = publishProperty;
        this.publishMultiple = publishMultiple;
        this.listMy = listMy;
        this.listPublished = listPublished;
        this.getProperty = getProperty;
        this.updateProperty = updateProperty;
        this.unlistProperty = unlistProperty;
        this.relistProperty = relistProperty;
        this.deleteProperty = deleteProperty;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a single property (DRAFT by default, can publish immediately)",
            description = "Pass status=PUBLISHED in query string to publish it directly; otherwise stays DRAFT.")
    @ApiResponse(responseCode = "201", description = "Property created")
    @ResponseStatus(HttpStatus.CREATED)
    PropertyDetailResponse createOne(@Valid @RequestBody PropertyPayload body,
            @RequestParam(defaultValue = "DRAFT") PropertyStatus status) {
        var summary = publishProperty.execute(actors.currentActor(), toData(body), status);
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return PropertyDetailResponse.of(summary, photos);
    }

    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish multiple properties in one call",
            description = "Accepts an array of property payloads. Each is validated independently. "
                    + "Pass status=PUBLISHED to publish them all directly. Returns the properties created and the number "
                    + "actually published (those that had required fields).")
    @ApiResponse(responseCode = "201", description = "Batch of properties created")
    @ResponseStatus(HttpStatus.CREATED)
    BatchResponse createBatch(@Valid @RequestBody BatchPayload body,
            @RequestParam(defaultValue = "DRAFT") PropertyStatus status) {
        var data = body.properties().stream().map(this::toData).toList();
        var result = publishMultiple.execute(actors.currentActor(), data, status);
        var items = result.created().stream()
                .map(s -> {
                    var photos = mediaAttachmentService.resolveReadUrls(s.mediaIds());
                    return PropertyDetailResponse.of(s, photos);
                })
                .toList();
        return new BatchResponse(items, result.publishedCount());
    }

    @GetMapping("/me")
    @Operation(summary = "List my properties (as owner or manager)",
            description = "Filter by role=OWNER or role=MANAGER; default returns both. "
                    + "Also filters by status, manager, and limit.")
    List<PropertyDetailResponse> mine(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) PropertyStatus status,
            @RequestParam(required = false) UUID manager,
            @RequestParam(defaultValue = "100") int limit) {
        return listMy.execute(actors.currentActor(), role, status, manager, limit).stream()
                .map(s -> {
                    var photos = mediaAttachmentService.resolveReadUrls(s.mediaIds());
                    return PropertyDetailResponse.of(s, photos);
                })
                .toList();
    }

    @GetMapping
    @Operation(summary = "Search published properties with filters",
            description = "Public search. Accepts type, city, zone, min/max rent, bedrooms, bathrooms, min surface, page, size.")
    List<PropertyDetailResponse> search(
            @RequestParam(required = false) PropertyType type,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) BigDecimal minRent,
            @RequestParam(required = false) BigDecimal maxRent,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) Integer minBathrooms,
            @RequestParam(required = false) Double minSurface,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return listPublished.execute(type, city, zone, minRent, maxRent, minBedrooms, minBathrooms, minSurface, page, size)
                .stream()
                .map(s -> {
                    var photos = mediaAttachmentService.resolveReadUrls(s.mediaIds());
                    return PropertyDetailResponse.of(s, photos);
                })
                .toList();
    }

    @GetMapping("/{propertyId}")
    @Operation(summary = "Get one property by ID")
    PropertyDetailResponse detail(@PathVariable UUID propertyId) {
        var summary = getProperty.execute(actors.currentActor(), propertyId);
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return PropertyDetailResponse.of(summary, photos);
    }

    @PutMapping(value = "/{propertyId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a property (owner or manager only)")
    PropertyDetailResponse update(@PathVariable UUID propertyId, @Valid @RequestBody PropertyPayload body) {
        var patch = new com.fixup.properties.domain.Property.UpdatePatch(
                body.type(), body.title(), body.description(),
                body.addressStreet(), body.addressNumber(), body.addressFloor(), body.addressApartment(),
                body.city(), body.zone(), body.postalCode(),
                body.latitude(), body.longitude(),
                body.surfaceM2(), body.coveredSurfaceM2(),
                body.bedrooms(), body.bathrooms(), body.coveredParkingSpots(),
                body.hasBalcony(), body.hasTerrace(), body.hasGarden(),
                body.hasElevator(), body.hasPool(), body.hasSecurity(),
                body.petsAllowed(), body.furnished(),
                body.amenities(), body.monthlyRentSuggestion(), body.monthlyCondoFee(),
                body.mediaIds());
        var summary = updateProperty.execute(actors.currentActor(), propertyId, patch);
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return PropertyDetailResponse.of(summary, photos);
    }

    @PatchMapping("/{propertyId}/unlist")
    @Operation(summary = "Unlist a PUBLISHED property (owner or manager only)")
    PropertyDetailResponse unlist(@PathVariable UUID propertyId) {
        var summary = unlistProperty.execute(actors.currentActor(), propertyId);
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return PropertyDetailResponse.of(summary, photos);
    }

    @PatchMapping("/{propertyId}/relist")
    @Operation(summary = "Relist an UNLISTED property (owner or manager only)")
    PropertyDetailResponse relist(@PathVariable UUID propertyId) {
        var summary = relistProperty.execute(actors.currentActor(), propertyId);
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return PropertyDetailResponse.of(summary, photos);
    }

    @DeleteMapping("/{propertyId}")
    @Operation(summary = "Soft-delete a property (owner or manager only)")
    void delete(@PathVariable UUID propertyId) {
        deleteProperty.execute(actors.currentActor(), propertyId);
    }

    private PublishProperty.NewProperty toData(PropertyPayload body) {
        return new PublishProperty.NewProperty(
                body.type(), body.title(), body.description(),
                body.addressStreet(), body.addressNumber(), body.addressFloor(), body.addressApartment(),
                body.city(), body.zone(), body.postalCode(),
                body.latitude(), body.longitude(),
                body.surfaceM2(), body.coveredSurfaceM2(),
                body.bedrooms(), body.bathrooms(), body.coveredParkingSpots(),
                body.hasBalcony(), body.hasTerrace(), body.hasGarden(),
                body.hasElevator(), body.hasPool(), body.hasSecurity(),
                body.petsAllowed(), body.furnished(),
                body.amenities(), body.monthlyRentSuggestion(), body.monthlyCondoFee(),
                body.mediaIds(), body.managerUserId());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record PropertyPayload(
            @NotNull PropertyType type,
            @NotBlank @Size(max = 150) String title,
            @Size(max = 4000) String description,
            @Size(max = 200) String addressStreet,
            @Size(max = 30) String addressNumber,
            @Size(max = 20) String addressFloor,
            @Size(max = 20) String addressApartment,
            @NotBlank @Size(max = 200) String city,
            @NotBlank @Size(max = 100) String zone,
            @Size(max = 20) String postalCode,
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
            @Size(max = 50) List<@Size(max = 80) String> amenities,
            BigDecimal monthlyRentSuggestion,
            BigDecimal monthlyCondoFee,
            @Size(max = 30) List<@NotNull UUID> mediaIds,
            UUID managerUserId) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record BatchPayload(@NotNull @Size(max = 50) List<@Valid PropertyPayload> properties) {
    }

    record PropertyDetailResponse(
            UUID id,
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
            List<PhotoResponse> photos,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant unlistedAt) {

        static PropertyDetailResponse of(PropertySummary s, List<SignedMediaView> media) {
            var photos = media == null ? List.<PhotoResponse>of()
                    : media.stream().map(p -> new PhotoResponse(p.mediaId(), p.readUrl(), p.readUrlExpiresAt())).toList();
            return new PropertyDetailResponse(s.id(), s.type(), s.status(), s.title(), s.description(),
                    s.addressStreet(), s.addressNumber(), s.addressFloor(), s.addressApartment(),
                    s.city(), s.zone(), s.postalCode(),
                    s.latitude(), s.longitude(),
                    s.surfaceM2(), s.coveredSurfaceM2(),
                    s.bedrooms(), s.bathrooms(), s.coveredParkingSpots(),
                    s.hasBalcony(), s.hasTerrace(), s.hasGarden(),
                    s.hasElevator(), s.hasPool(), s.hasSecurity(),
                    s.petsAllowed(), s.furnished(), s.amenities(),
                    s.monthlyRentSuggestion(), s.monthlyCondoFee(),
                    photos, s.createdAt(), s.updatedAt(), s.publishedAt(), s.unlistedAt());
        }
    }

    record PhotoResponse(UUID mediaId, String readUrl, Instant readUrlExpiresAt) {
    }

    record BatchResponse(List<PropertyDetailResponse> created, int publishedCount) {
    }
}
