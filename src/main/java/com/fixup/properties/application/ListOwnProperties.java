package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.domain.Properties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ListOwnProperties {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;

    public ListOwnProperties(Properties properties, CurrentActorProvider currentActorProvider) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
    }

    public List<PropertySummary> execute() {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        return properties.findByOwnerUserIdOrderByCreatedAtDesc(actor.internalUserId())
            .stream()
            .map(PropertySummary::from)
            .toList();
    }
}
