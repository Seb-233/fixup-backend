package com.fixup.properties.web;

import com.fixup.properties.application.GeocodeAddress;
import com.fixup.properties.application.ReverseGeocodeAddress;
import com.fixup.properties.domain.GeocodingCandidate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/locations")
class LocationController {
    static final String ATTRIBUTION_HEADER = "X-Data-Attribution";
    static final String OPENSTREETMAP_ATTRIBUTION = "\u00A9 OpenStreetMap contributors";

    private final GeocodeAddress geocodeAddress;
    private final ReverseGeocodeAddress reverseGeocodeAddress;

    LocationController(GeocodeAddress geocodeAddress, ReverseGeocodeAddress reverseGeocodeAddress) {
        this.geocodeAddress = geocodeAddress;
        this.reverseGeocodeAddress = reverseGeocodeAddress;
    }

    @GetMapping(value = "/reverse-geocode", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resolve map coordinates to a readable address")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<GeocodingCandidate>> reverseGeocode(
            @RequestParam BigDecimal latitude, @RequestParam BigDecimal longitude) {
        return ResponseEntity.ok().header(ATTRIBUTION_HEADER, OPENSTREETMAP_ATTRIBUTION)
                .body(reverseGeocodeAddress.execute(latitude, longitude));
    }

    @GetMapping(value = "/geocode", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Geocode an address in Colombia",
            description = "Explicit address search backed by a configurable provider. "
                    + "Results require attribution to OpenStreetMap contributors; this endpoint is not autocomplete.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Zero or more location candidates",
                headers = @Header(name = ATTRIBUTION_HEADER,
                        description = "Required data attribution", schema = @Schema(type = "string")),
                content = @io.swagger.v3.oas.annotations.media.Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        array = @ArraySchema(schema = @Schema(implementation = GeocodingCandidate.class)))),
        @ApiResponse(responseCode = "400", description = "Missing, blank or oversized query"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token"),
        @ApiResponse(responseCode = "403", description = "Role is not allowed to geocode addresses"),
        @ApiResponse(responseCode = "429", description = "External geocoding request rate exceeded"),
        @ApiResponse(responseCode = "503", description = "Geocoding provider is unavailable")
    })
    ResponseEntity<List<GeocodingCandidate>> geocode(
            @Parameter(required = true,
                    description = "Complete address submitted explicitly; not an autocomplete query",
                    schema = @Schema(maxLength = GeocodeAddress.MAX_QUERY_LENGTH))
            @RequestParam String query) {
        return ResponseEntity.ok()
                .header(ATTRIBUTION_HEADER, OPENSTREETMAP_ATTRIBUTION)
                .body(geocodeAddress.execute(query));
    }
}
