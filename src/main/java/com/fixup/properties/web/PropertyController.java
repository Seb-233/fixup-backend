package com.fixup.properties.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.ListingType;
import com.fixup.properties.api.PropertyType;
import com.fixup.properties.application.AssignPropertyManager;
import com.fixup.properties.application.BrowseCatalog;
import com.fixup.properties.application.BulkImportProperties;
import com.fixup.properties.application.CatalogPage;
import com.fixup.properties.application.ChangePropertyPublication;
import com.fixup.properties.application.GetPublicListing;
import com.fixup.properties.application.PropertyChanges;
import com.fixup.properties.application.PublicListingView;
import com.fixup.properties.application.UpdateProperty;
import com.fixup.properties.domain.CatalogQuery;
import com.fixup.properties.application.BulkImportResult;
import com.fixup.properties.application.BulkValidationResult;
import com.fixup.properties.application.CreateProperty;
import com.fixup.properties.application.GetProperty;
import com.fixup.properties.application.ListOwnProperties;
import com.fixup.properties.application.NewProperty;
import com.fixup.properties.application.PropertySearchResult;
import com.fixup.properties.application.PropertySummary;
import com.fixup.properties.application.SearchPublishedProperties;
import com.fixup.properties.domain.OwnershipScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/properties")
class PropertyController {

    private final CreateProperty createProperty;
    private final ListOwnProperties listOwnProperties;
    private final GetProperty getProperty;
    private final SearchPublishedProperties searchPublishedProperties;
    private final BulkImportProperties bulkImportProperties;
    private final CurrentActorProvider currentActorProvider;
    private final UpdateProperty updateProperty;
    private final ChangePropertyPublication publication;
    private final AssignPropertyManager assignManager;
    private final BrowseCatalog browseCatalog;
    private final GetPublicListing getPublicListing;

    PropertyController(CreateProperty createProperty, ListOwnProperties listOwnProperties, GetProperty getProperty,
                       SearchPublishedProperties searchPublishedProperties,
                       BulkImportProperties bulkImportProperties, CurrentActorProvider currentActorProvider,
                       UpdateProperty updateProperty, ChangePropertyPublication publication,
                       AssignPropertyManager assignManager, BrowseCatalog browseCatalog,
                       GetPublicListing getPublicListing) {
        this.createProperty = createProperty;
        this.listOwnProperties = listOwnProperties;
        this.getProperty = getProperty;
        this.searchPublishedProperties = searchPublishedProperties;
        this.bulkImportProperties = bulkImportProperties;
        this.currentActorProvider = currentActorProvider;
        this.updateProperty = updateProperty;
        this.publication = publication;
        this.assignManager = assignManager;
        this.browseCatalog = browseCatalog;
        this.getPublicListing = getPublicListing;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PropertySummary create(@Valid @RequestBody NewProperty command) {
        return createProperty.execute(command);
    }

    @GetMapping("/me")
    public List<PropertySummary> listOwn() {
        return listOwnProperties.execute();
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Search published properties by geographic radius")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Published properties ordered by distance",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = @ArraySchema(schema = @Schema(implementation = PropertySearchResult.class)))),
        @ApiResponse(responseCode = "400", description = "Missing, malformed or out-of-range parameter"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
        @ApiResponse(responseCode = "403", description = "Role or ownership scope is not allowed")
    })
    public List<PropertySearchResult> search(
            @Parameter(required = true, schema = @Schema(minimum = "-90", maximum = "90"))
            @RequestParam BigDecimal latitude,
            @Parameter(required = true, schema = @Schema(minimum = "-180", maximum = "180"))
            @RequestParam BigDecimal longitude,
            @Parameter(required = true, schema = @Schema(exclusiveMinimum = true, minimum = "0"))
            @RequestParam BigDecimal radiusKm,
            @Parameter(description = "Ownership filter. TENANT and PLATFORM_ADMIN may only use ALL",
                    schema = @Schema(allowableValues = {"ALL", "MINE", "OTHERS"}, defaultValue = "ALL"))
            @RequestParam(defaultValue = "ALL") OwnershipScope ownershipScope) {
        return searchPublishedProperties.execute(latitude, longitude, radiusKm, ownershipScope);
    }

    @GetMapping("/{propertyId}")
    public PropertySummary get(@PathVariable UUID propertyId) {
        return getProperty.execute(propertyId);
    }

    @PutMapping("/{propertyId}")
    @Operation(summary = "Edit a property and its listing (FR-UC-02)",
            description = "Only the owner, the delegated real estate manager or a platform admin may edit. "
                    + "photoMediaIds replaces the gallery in the given order.")
    public PropertySummary update(@PathVariable UUID propertyId, @Valid @RequestBody PropertyChanges changes) {
        return updateProperty.execute(propertyId, changes);
    }

    @PostMapping("/{propertyId}/publish")
    @Operation(summary = "Publish a draft or withdrawn property in the free catalog (FR-UC-02)")
    public PropertySummary publish(@PathVariable UUID propertyId) {
        return publication.publish(propertyId);
    }

    @PostMapping("/{propertyId}/unpublish")
    @Operation(summary = "Withdraw a published property from the catalog without deleting it (FR-UC-02)")
    public PropertySummary unpublish(@PathVariable UUID propertyId) {
        return publication.unpublish(propertyId);
    }

    @PutMapping("/{propertyId}/manager")
    @Operation(summary = "Delegate the property to a real estate manager, or revoke it with null (FR-UC-11)")
    public PropertySummary delegate(@PathVariable UUID propertyId, @RequestBody ManagerAssignment body) {
        return assignManager.execute(propertyId, body.managerUserId());
    }

    @GetMapping("/managers")
    @Operation(summary = "Active real estate managers an owner can delegate to (FR-UC-11)")
    public List<ManagerOption> managers() {
        return assignManager.availableManagers().stream()
                .map(card -> new ManagerOption(card.id(), card.displayName()))
                .toList();
    }

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Browse published properties with filters (FR-UC-06, FR-UC-07)")
    public CatalogPage catalog(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) ListingType listingType,
            @RequestParam(required = false) PropertyType propertyType,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) Integer stratum,
            @RequestParam(defaultValue = "false") boolean sealOnly,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "NEWEST") CatalogQuery.CatalogSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return browseCatalog.execute(new CatalogQuery(city, listingType, propertyType, minPrice, maxPrice,
                minBedrooms, stratum, sealOnly, q, sort, page, size));
    }

    @GetMapping("/{propertyId}/listing")
    @Operation(summary = "Public listing with photos, Fix&Rent seal and work history (FR-UC-02, FR-UC-07)")
    public PublicListingView listing(@PathVariable UUID propertyId) {
        return getPublicListing.execute(propertyId);
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ManagerAssignment(@Schema(types = {"string", "null"}) UUID managerUserId) {
    }

    @Schema(requiredProperties = {"id", "displayName"})
    record ManagerOption(UUID id, String displayName) {
    }

    @PostMapping(value = "/bulk/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkValidationResult bulkValidate(@RequestParam("file") MultipartFile file) throws IOException {
        return bulkImportProperties.validate(file);
    }

    @PostMapping(value = "/bulk/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public BulkImportResult bulkImport(@RequestParam("file") MultipartFile file) throws IOException {
        var actor = currentActorProvider.currentActor();
        return bulkImportProperties.doImport(file, actor);
    }
}
