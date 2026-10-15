package com.fixup.properties.web;

import com.fixup.properties.application.CreateProperty;
import com.fixup.properties.application.GetProperty;
import com.fixup.properties.application.ListOwnProperties;
import com.fixup.properties.application.NewProperty;
import com.fixup.properties.application.PropertySummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/properties")
class PropertyController {

    private final CreateProperty createProperty;
    private final ListOwnProperties listOwnProperties;
    private final GetProperty getProperty;

    PropertyController(CreateProperty createProperty, ListOwnProperties listOwnProperties, GetProperty getProperty) {
        this.createProperty = createProperty;
        this.listOwnProperties = listOwnProperties;
        this.getProperty = getProperty;
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
}
