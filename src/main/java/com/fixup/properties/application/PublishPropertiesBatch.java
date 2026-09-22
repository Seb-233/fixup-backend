package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.properties.api.PropertyBatchPublished;
import com.fixup.properties.api.PropertyConflictException;
import com.fixup.properties.api.PropertyNotFoundException;
import com.fixup.properties.domain.Properties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FR-UC-12: publicación en bloque, que es la razón de ser del caso de uso. Un propietario con
 * veinte inmuebles no los publica de a uno.
 *
 * <p>El lote es atómico: si un inmueble del lote no es suyo o no admite la transición, no se
 * publica ninguno. Publicar la mitad dejaría al propietario sin saber qué quedó ofertado.
 * Se emite un único {@link PropertyBatchPublished} y no un evento por inmueble, para que la
 * bandeja de notificaciones reciba un aviso y no veinte.
 */
@Service
@Transactional
public class PublishPropertiesBatch {

    public static final int MAX_BATCH_SIZE = 50;

    private final Properties properties;
    private final CurrentActorProvider currentActorProvider;
    private final ApplicationEventPublisher events;

    public PublishPropertiesBatch(Properties properties, CurrentActorProvider currentActorProvider,
            ApplicationEventPublisher events) {
        this.properties = properties;
        this.currentActorProvider = currentActorProvider;
        this.events = events;
    }

    public BatchResult execute(List<BatchItem> items) {
        var actor = currentActorProvider.currentActor();
        PropertyAccess.requireActiveOwner(actor);

        if (items == null || items.isEmpty()) {
            throw new PropertyConflictException("EMPTY_BATCH", "The batch contains no property");
        }
        if (items.size() > MAX_BATCH_SIZE) {
            throw new PropertyConflictException("BATCH_TOO_LARGE",
                "A batch publishes at most " + MAX_BATCH_SIZE + " properties");
        }
        var distinctIds = items.stream().map(BatchItem::propertyId).distinct().count();
        if (distinctIds != items.size()) {
            throw new PropertyConflictException("DUPLICATE_PROPERTY",
                "The batch names the same property more than once");
        }

        var now = Instant.now();
        var published = new ArrayList<PropertySummary>(items.size());
        var publishedIds = new ArrayList<UUID>(items.size());

        for (var item : items) {
            var property = properties.findById(item.propertyId())
                .orElseThrow(() -> new PropertyNotFoundException(item.propertyId()));
            PropertyAccess.requireCanPublish(actor, property);

            var details = item.details();
            property.publish(details.type(), details.title(), details.description(), details.zone(),
                details.monthlyRentSuggestion(), now);
            published.add(PropertySummary.from(properties.save(property)));
            publishedIds.add(property.id());
        }

        events.publishEvent(new PropertyBatchPublished(List.copyOf(publishedIds),
            actor.internalUserId(), publishedIds.size(), now));

        return new BatchResult(List.copyOf(published), published.size());
    }

    public record BatchItem(UUID propertyId, PublicationDetails details) {}

    public record BatchResult(List<PropertySummary> published, int publishedCount) {}
}
