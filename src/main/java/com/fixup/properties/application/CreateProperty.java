package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.properties.api.PropertyPublished;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CreateProperty {
    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;
    private final MediaAttachmentService media;
    private final ApplicationEventPublisher events;

    public CreateProperty(Properties properties, CurrentActorProvider currentActorProvider,
                          MediaAttachmentService media, ApplicationEventPublisher events) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
        this.media = media;
        this.events = events;
    }

    public PropertySummary execute(NewProperty command) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        boolean publish = command.publish() == null || command.publish();
        var property = Property.register(
            actor.internalUserId(),
            command.name(),
            command.address(),
            command.city(),
            command.areaM2(),
            command.latitude(),
            command.longitude(),
            command.listing(),
            publish,
            null
        );
        if (command.photoMediaIds() != null && !command.photoMediaIds().isEmpty()) {
            media.attachPropertyPhotos(actor.internalUserId(), command.photoMediaIds());
            property.replacePhotos(command.photoMediaIds(), property.createdAt());
        }

        var saved = properties.save(property);
        if (saved.status() == PropertyStatus.PUBLISHED) {
            events.publishEvent(new PropertyPublished(saved.id(), saved.ownerUserId(), saved.name()));
        }
        return PropertySummary.from(saved);
    }
}
