package com.fixup.properties.application;

import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.properties.api.PropertyBulkImportFinished;
import com.fixup.properties.api.PropertyPublished;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** FR-UC-02, FR-UC-10 y FR-UC-12: confirma al publicador que su aviso ya está en el catálogo. */
@Component
class PropertyNotifications {
    private final ApplicationEventPublisher events;

    PropertyNotifications(ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener
    void on(PropertyPublished event) {
        events.publishEvent(NotificationRequested.of(event.ownerUserId(), NotificationType.PROPERTY_PUBLISHED,
                "Inmueble publicado", "\"" + event.name() + "\" ya aparece en el catálogo de Fix&Rent.",
                "/properties/" + event.propertyId(), event.propertyId(), "PROPERTY"));
    }

    @EventListener
    void on(PropertyBulkImportFinished event) {
        int total = event.importedCount() + event.failedCount();
        events.publishEvent(new NotificationRequested(event.publisherUserId(), NotificationType.PROPERTY_BULK_FINISHED,
                "Carga masiva terminada",
                "Se publicaron " + event.importedCount() + " de " + total + " inmuebles del archivo.",
                "/properties", event.batchId(), "PROPERTY_IMPORT",
                Map.of("total", String.valueOf(total), "imported", String.valueOf(event.importedCount()))));
    }
}
