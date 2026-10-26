package com.fixup.properties.application;

import com.fixup.media.api.MediaAttachmentService;
import com.fixup.media.api.SignedMediaView;
import com.fixup.properties.domain.Property;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Resuelve en una sola llamada la URL firmada de la foto de portada de cada inmueble. */
@Component
class PropertyCovers {
    private final MediaAttachmentService media;

    PropertyCovers(MediaAttachmentService media) {
        this.media = media;
    }

    Map<UUID, String> of(Collection<Property> properties) {
        var coverByProperty = new HashMap<UUID, UUID>();
        properties.forEach(property -> {
            if (!property.photoMediaIds().isEmpty()) {
                coverByProperty.put(property.id(), property.photoMediaIds().get(0));
            }
        });
        if (coverByProperty.isEmpty()) {
            return Map.of();
        }
        var urlByMedia = new HashMap<UUID, String>();
        for (SignedMediaView view : media.resolveReadUrls(List.copyOf(coverByProperty.values()))) {
            urlByMedia.put(view.mediaId(), view.readUrl());
        }
        var result = new HashMap<UUID, String>();
        coverByProperty.forEach((propertyId, mediaId) -> {
            var url = urlByMedia.get(mediaId);
            if (url != null) {
                result.put(propertyId, url);
            }
        });
        return result;
    }
}
