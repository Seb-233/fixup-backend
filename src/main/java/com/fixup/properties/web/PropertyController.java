package com.fixup.properties.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.application.BulkImportProperties;
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

    PropertyController(CreateProperty createProperty, ListOwnProperties listOwnProperties, GetProperty getProperty,
                       SearchPublishedProperties searchPublishedProperties,
                       BulkImportProperties bulkImportProperties, CurrentActorProvider currentActorProvider) {
        this.createProperty = createProperty;
        this.listOwnProperties = listOwnProperties;
        this.getProperty = getProperty;
        this.searchPublishedProperties = searchPublishedProperties;
        this.bulkImportProperties = bulkImportProperties;
        this.currentActorProvider = currentActorProvider;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PropertySummary create(@RequestBody NewProperty command) {
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
