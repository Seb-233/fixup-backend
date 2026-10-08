package com.fixup.notifications.application;

import com.fixup.notifications.api.ContractCreatedEvent;
import com.fixup.notifications.api.ContractExpiredEvent;
import com.fixup.notifications.api.ContractExpiringSoonEvent;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.api.PropertyBulkImportFinishedEvent;
import com.fixup.notifications.api.PropertyPublishedEvent;
import com.fixup.notifications.api.PushNotificationGateway;
import com.fixup.notifications.api.PushNotificationPayload;
import com.fixup.notifications.api.QuotationAcceptedEvent;
import com.fixup.notifications.api.RequestNotifications;
import com.fixup.notifications.api.SlaBreachedEvent;
import com.fixup.notifications.api.SlaWarningRaisedEvent;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class NotificationDispatcher implements RequestNotifications {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final Notificaciones notificaciones;
    private final PushNotificationGateway pushGateway;
    private final Clock clock;

    NotificationDispatcher(Notificaciones notificaciones, PushNotificationGateway pushGateway, Clock clock) {
        this.notificaciones = notificaciones;
        this.pushGateway = pushGateway;
        this.clock = clock;
    }

    @Override
    public void urgentRequestCreated(UUID requestId, UUID ownerUserId, String title, Instant occurredAt) {
        Notification notification = Notification.create(
                UUID.randomUUID(), ownerUserId, NotificationType.REQUEST_CREATED_URGENT,
                "Solicitud urgente creada: " + title,
                "Tu solicitud urgente fue recibida y asignada a la cola de prioridad. Recibirás propuestas de técnicos pronto. SLA máximo: 48h.",
                "/requests/" + requestId, requestId, "REQUEST", Map.of("urgency", "URGENT"), occurredAt);
        // Keep the existing synchronous save in the caller's transaction, without adding push delivery.
        notificaciones.save(notification);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQuotationAccepted(QuotationAcceptedEvent event) {
        Instant now = Instant.now(clock);
        String title = "Cotización aceptada";
        String body = "Tu cotización para la solicitud ha sido aceptada.";
        String navigateTo = "/quotations/mine";

        Notification notification = Notification.create(
                UUID.randomUUID(),
                event.fixerUserId(),
                NotificationType.QUOTATION_ACCEPTED,
                title, body, navigateTo,
                event.quotationId(), "QUOTATION",
                Map.of("requestId", event.requestId().toString()),
                now);

        persistAndPush(notification);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaWarningRaised(SlaWarningRaisedEvent event) {
        Instant now = Instant.now(clock);
        String title = "Advertencia SLA";
        String body = "Una solicitud está próxima a incumplir su SLA.";
        String navigateTo = "/administration/sla-board";
        Map<String, String> data = Map.of("requestId", event.requestId().toString());

        Notification ownerNotif = Notification.create(
                UUID.randomUUID(), event.ownerUserId(),
                NotificationType.REQUEST_SLA_WARNING,
                title, body, navigateTo,
                event.requestId(), "REQUEST", data, now);
        persistAndPush(ownerNotif);

        Notification adminNotif = Notification.create(
                UUID.randomUUID(), event.platformAdminUserId(),
                NotificationType.REQUEST_SLA_WARNING,
                title, body, navigateTo,
                event.requestId(), "REQUEST", data, now);
        persistAndPush(adminNotif);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlaBreached(SlaBreachedEvent event) {
        Instant now = Instant.now(clock);
        String title = "SLA incumplido";
        String body = "Una solicitud ha superado el tiempo máximo de respuesta.";
        String navigateTo = "/administration/sla-board";
        Map<String, String> data = Map.of("requestId", event.requestId().toString());

        Notification ownerNotif = Notification.create(
                UUID.randomUUID(), event.ownerUserId(),
                NotificationType.REQUEST_SLA_BREACHED,
                title, body, navigateTo,
                event.requestId(), "REQUEST", data, now);
        persistAndPush(ownerNotif);

        Notification adminNotif = Notification.create(
                UUID.randomUUID(), event.platformAdminUserId(),
                NotificationType.REQUEST_SLA_BREACHED,
                title, body, navigateTo,
                event.requestId(), "REQUEST", data, now);
        persistAndPush(adminNotif);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPropertyPublished(PropertyPublishedEvent event) {
        Instant now = Instant.now(clock);
        String title = "Propiedad publicada";
        String body = "Tu propiedad ha sido publicada exitosamente.";
        String navigateTo = "/properties/" + event.propertyId();

        Notification notification = Notification.create(
                UUID.randomUUID(), event.ownerUserId(),
                NotificationType.PROPERTY_PUBLISHED,
                title, body, navigateTo,
                event.propertyId(), "PROPERTY", null, now);
        persistAndPush(notification);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPropertyBulkImportFinished(PropertyBulkImportFinishedEvent event) {
        Instant now = Instant.now(clock);
        String title = "Importación de propiedades finalizada";
        String body = String.format("Se importaron %d de %d propiedades.",
                event.importedCount(), event.totalProperties());
        String navigateTo = "/properties";
        Map<String, String> data = Map.of(
                "total", String.valueOf(event.totalProperties()),
                "imported", String.valueOf(event.importedCount()));

        Notification notification = Notification.create(
                UUID.randomUUID(), event.userId(),
                NotificationType.PROPERTY_BULK_FINISHED,
                title, body, navigateTo,
                event.importId(), "PROPERTY_IMPORT", data, now);
        persistAndPush(notification);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContractCreated(ContractCreatedEvent event) {
        Instant now = Instant.now(clock);
        String title = "Contrato creado";
        String body = "Se ha creado un nuevo contrato.";
        String navigateTo = "/contracts/" + event.contractId();

        for (UUID recipient : List.of(event.ownerUserId(), event.tenantUserId())) {
            Notification notification = Notification.create(
                    UUID.randomUUID(), recipient,
                    NotificationType.CONTRACT_CREATED,
                    title, body, navigateTo,
                    event.contractId(), "CONTRACT", null, now);
            persistAndPush(notification);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContractExpiringSoon(ContractExpiringSoonEvent event) {
        Instant now = Instant.now(clock);
        NotificationType type = (event.daysUntilExpiry() <= 7)
                ? NotificationType.CONTRACT_EXPIRING_7D
                : NotificationType.CONTRACT_EXPIRING_30D;
        String title = String.format("Contrato por vencer en %d días", event.daysUntilExpiry());
        String body = "Tu contrato vencerá pronto. Revisa los detalles para renovarlo.";
        String navigateTo = "/contracts/" + event.contractId();
        Map<String, String> data = Map.of("daysUntilExpiry", String.valueOf(event.daysUntilExpiry()));

        for (UUID recipient : List.of(event.ownerUserId(), event.tenantUserId())) {
            Notification notification = Notification.create(
                    UUID.randomUUID(), recipient, type,
                    title, body, navigateTo,
                    event.contractId(), "CONTRACT", data, now);
            persistAndPush(notification);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContractExpired(ContractExpiredEvent event) {
        Instant now = Instant.now(clock);
        String title = "Contrato vencido";
        String body = "El contrato ha vencido. Por favor toma las acciones necesarias.";
        String navigateTo = "/contracts/" + event.contractId();

        for (UUID recipient : List.of(event.ownerUserId(), event.tenantUserId())) {
            Notification notification = Notification.create(
                    UUID.randomUUID(), recipient,
                    NotificationType.CONTRACT_EXPIRED,
                    title, body, navigateTo,
                    event.contractId(), "CONTRACT", null, now);
            persistAndPush(notification);
        }
    }

    private void persistAndPush(Notification notification) {
        try {
            notificaciones.save(notification);
        } catch (Exception e) {
            LOG.error("Failed to persist notification id={} recipient={}",
                    notification.id(), notification.recipientUserId(), e);
            return;
        }

        try {
            PushNotificationPayload payload = new PushNotificationPayload(
                    notification.navigateTo(),
                    notification.entityId(),
                    notification.entityType(),
                    notification.data());
            var push = new PushNotificationGateway.PushNotification(
                    notification.recipientUserId(),
                    notification.title(),
                    notification.body(),
                    payload);
            pushGateway.send(push);
        } catch (Exception e) {
            LOG.warn("Best-effort push failed for notification id={}: {}",
                    notification.id(), e.getMessage());
        }
    }
}
