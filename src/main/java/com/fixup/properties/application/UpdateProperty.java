package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-02: el propietario o su administrador corrigen el inmueble y el aviso. */
@Service
public class UpdateProperty {
    private final Properties properties;
    private final CurrentActorProvider actors;
    private final MediaAttachmentService media;

    UpdateProperty(Properties properties, CurrentActorProvider actors, MediaAttachmentService media) {
        this.properties = properties;
        this.actors = actors;
        this.media = media;
    }

    @Transactional
    public PropertySummary execute(UUID propertyId, PropertyChanges changes) {
        var actor = actors.currentActor();
        var property = properties.findById(propertyId).orElseThrow(() -> new PropertyNotFoundException(propertyId));
        PropertyAccess.requireCanManage(actor, property);

        var now = Instant.now();
        property.updateDetails(changes.name(), changes.address(), changes.city(), changes.areaM2(),
                changes.latitude(), changes.longitude(), changes.listing(), now);
        if (changes.photoMediaIds() != null) {
            var current = new HashSet<>(property.photoMediaIds());
            List<UUID> added = changes.photoMediaIds().stream().filter(id -> !current.contains(id)).toList();
            media.attachPropertyPhotos(actor.internalUserId(), added);
            property.replacePhotos(changes.photoMediaIds(), now);
        }
        return PropertySummary.from(properties.save(property));
    }
}
