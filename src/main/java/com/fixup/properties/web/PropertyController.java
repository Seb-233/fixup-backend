package com.fixup.properties.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.application.BulkImportProperties;
import com.fixup.properties.application.BulkImportResult;
import com.fixup.properties.application.BulkValidationResult;
import com.fixup.properties.application.CreateProperty;
import com.fixup.properties.application.GetProperty;
import com.fixup.properties.application.ListOwnProperties;
import com.fixup.properties.application.NewProperty;
import com.fixup.properties.application.PropertySummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/properties")
class PropertyController {

    private final CreateProperty createProperty;
    private final ListOwnProperties listOwnProperties;
    private final GetProperty getProperty;
    private final BulkImportProperties bulkImportProperties;
    private final CurrentActorProvider currentActorProvider;

    PropertyController(CreateProperty createProperty, ListOwnProperties listOwnProperties, GetProperty getProperty,
                       BulkImportProperties bulkImportProperties, CurrentActorProvider currentActorProvider) {
        this.createProperty = createProperty;
        this.listOwnProperties = listOwnProperties;
        this.getProperty = getProperty;
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
