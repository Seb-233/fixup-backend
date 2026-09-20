package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CreateProperty {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;

    public CreateProperty(Properties properties, CurrentActorProvider currentActorProvider) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
    }

    public PropertySummary execute(NewProperty command) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        var property = Property.create(
            actor.internalUserId(),
            command.name(),
            command.address(),
            command.city(),
            command.areaM2()
        );

        return PropertySummary.from(properties.save(property));
    }
}
