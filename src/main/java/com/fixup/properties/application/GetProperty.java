package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetProperty {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;

    public GetProperty(Properties properties, CurrentActorProvider currentActorProvider) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
    }

    public PropertySummary execute(UUID id) {
        var property = properties.findById(id)
            .orElseThrow(() -> new PropertyNotFoundException(id));
            
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireCanRead(actor, property);

        return PropertySummary.from(property);
    }
}
