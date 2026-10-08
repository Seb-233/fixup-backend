package com.fixup.notifications.application;

import com.fixup.notifications.api.DemoNotifications;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class DemoNotificationSeed implements DemoNotifications {
    private static final Logger log = LoggerFactory.getLogger(DemoNotificationSeed.class);
    private final Notificaciones notificaciones;

    DemoNotificationSeed(Notificaciones notificaciones) {
        this.notificaciones = notificaciones;
    }

    @Override
    public void seedForOwner(UUID ownerUserId) {
        long existing = notificaciones.countUnreadByRecipientUserId(ownerUserId);
        if (existing > 0) {
            log.info("Demo notifications already present for owner={} (count={}). Skipping seed.",
                    ownerUserId, existing);
            return;
        }
        Instant now = Instant.now();

        Notification contractExpiring = Notification.create(
                UUID.randomUUID(),
                ownerUserId,
                NotificationType.CONTRACT_EXPIRING_30D,
                "Tu contrato vence en 30 días",
                "El contrato de arrendamiento del apartamento Centro vence el próximo mes. Contacta al inquilino para renovar.",
                "/contracts",
                null,
                "CONTRACT",
                Map.of("daysUntilExpiry", "30"),
                now.minusSeconds(3600));
        notificaciones.save(contractExpiring);

        Notification urgentRequest = Notification.create(
                UUID.randomUUID(),
                ownerUserId,
                NotificationType.REQUEST_CREATED_URGENT,
                "Fuga de gas reportada (urgente)",
                "Hemos recibido tu solicitud de reparación urgente. Un técnico será asignado en las próximas horas. SLA: 48h.",
                "/requests",
                null,
                "REQUEST",
                Map.of("urgency", "URGENT"),
                now.minusSeconds(1800));
        notificaciones.save(urgentRequest);

        Notification bulkFinished = Notification.create(
                UUID.randomUUID(),
                ownerUserId,
                NotificationType.PROPERTY_BULK_FINISHED,
                "Carga masiva terminada: 4 de 5 propiedades",
                "Se importaron exitosamente 4 propiedades. 1 fila fue rechazada por área inválida. Revisa el detalle en el panel.",
                "/administration/properties/bulk-upload",
                UUID.randomUUID(),
                "PROPERTY_IMPORT",
                Map.of("total", "5", "imported", "4"),
                now.minusSeconds(600));
        notificaciones.save(bulkFinished);

        log.info("Demo notifications seeded for owner={}: 3 rows (contract expiring + urgent request + bulk finished).",
                ownerUserId);
    }

}
