package com.fixup.properties.web;

import com.fixup.properties.api.PropertyType;
import com.fixup.properties.application.CreateProperty;
import com.fixup.properties.application.GetProperty;
import com.fixup.properties.application.ListOwnProperties;
import com.fixup.properties.application.NewProperty;
import com.fixup.properties.application.PropertySummary;
import com.fixup.properties.application.PublicationDetails;
import com.fixup.properties.application.PublishPropertiesBatch;
import com.fixup.properties.application.PublishProperty;
import com.fixup.properties.application.UnlistProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/properties")
class PropertyController {

    private final CreateProperty createProperty;
    private final ListOwnProperties listOwnProperties;
    private final GetProperty getProperty;
    private final PublishProperty publishProperty;
    private final PublishPropertiesBatch publishPropertiesBatch;
    private final UnlistProperty unlistProperty;

    PropertyController(CreateProperty createProperty, ListOwnProperties listOwnProperties, GetProperty getProperty,
            PublishProperty publishProperty, PublishPropertiesBatch publishPropertiesBatch,
            UnlistProperty unlistProperty) {
        this.createProperty = createProperty;
        this.listOwnProperties = listOwnProperties;
        this.getProperty = getProperty;
        this.publishProperty = publishProperty;
        this.publishPropertiesBatch = publishPropertiesBatch;
        this.unlistProperty = unlistProperty;
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

    // FR-UC-12: la publicación en bloque se declara antes que /{propertyId} por claridad; van por
    // métodos distintos, así que el enrutador no las confunde.
    @PostMapping("/publish-batch")
    public PublishPropertiesBatch.BatchResult publishBatch(@Valid @RequestBody BatchPublicationRequest body) {
        var items = body.properties().stream()
            .map(item -> new PublishPropertiesBatch.BatchItem(item.propertyId(), item.toDetails()))
            .toList();
        return publishPropertiesBatch.execute(items);
    }

    @GetMapping("/{propertyId}")
    public PropertySummary get(@PathVariable UUID propertyId) {
        return getProperty.execute(propertyId);
    }

    @PostMapping("/{propertyId}/publish")
    public PropertySummary publish(@PathVariable UUID propertyId,
            @Valid @RequestBody PublicationRequest body) {
        return publishProperty.execute(propertyId, body.toDetails());
    }

    @PostMapping("/{propertyId}/unlist")
    public PropertySummary unlist(@PathVariable UUID propertyId) {
        return unlistProperty.execute(propertyId);
    }

    record PublicationRequest(
            @NotNull PropertyType type,
            @NotNull String title,
            String description,
            @NotNull String zone,
            @NotNull BigDecimal monthlyRentSuggestion) {

        PublicationDetails toDetails() {
            return new PublicationDetails(type, title, description, zone, monthlyRentSuggestion);
        }
    }

    record BatchPublicationItem(
            @NotNull UUID propertyId,
            @NotNull PropertyType type,
            @NotNull String title,
            String description,
            @NotNull String zone,
            @NotNull BigDecimal monthlyRentSuggestion) {

        PublicationDetails toDetails() {
            return new PublicationDetails(type, title, description, zone, monthlyRentSuggestion);
        }
    }

    record BatchPublicationRequest(@NotEmpty List<@Valid BatchPublicationItem> properties) {}
}
