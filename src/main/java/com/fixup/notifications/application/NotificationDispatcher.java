package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.api.PushNotificationGateway;
import com.fixup.notifications.api.PushNotificationPayload;
import com.fixup.notifications.api.RequestNotifications;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * FR-UC-10: convierte los hechos de negocio en avisos para el usuario.
 *
 * <p>Todos los listeners corren después del commit del caso de uso que originó el hecho, y cada
 * uno abre su propia transacción (REQUIRES_NEW): en AFTER_COMMIT la transacción original ya
 * terminó, así que una escritura que se uniera a ella nunca llegaría a confirmarse. Un fallo al
 * guardar el aviso se registra y no deshace el caso de uso, que ya está confirmado.
 *
 * <p>notifications es una hoja del grafo de módulos: no conoce a ningún módulo de negocio. Cada
 * módulo traduce sus propios hechos a {@link NotificationRequested}, así ninguna dependencia nueva
 * hacia notifications puede cerrar un ciclo.
 */
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNotificationRequested(NotificationRequested event) {
        persistAndPush(Notification.create(UUID.randomUUID(), event.recipientUserId(), event.type(),
                event.title(), event.body(), event.navigateTo(), event.entityId(), event.entityType(),
                event.data(), Instant.now(clock)));
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
