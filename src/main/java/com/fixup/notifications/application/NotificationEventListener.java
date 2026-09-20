package com.fixup.notifications.application;

import com.fixup.notifications.api.NotificationType;
import com.fixup.quotations.api.QuotationAccepted;
import com.fixup.quotations.api.QuotationRejected;
import com.fixup.quotations.api.QuotationSubmitted;
import com.fixup.requests.api.RepairRequestAssigned;
import com.fixup.requests.api.RepairRequestCancelled;
import com.fixup.requests.api.RepairRequestCompleted;
import com.fixup.requests.api.RepairRequestOpened;
import com.fixup.requests.api.RepairRequestProgressStarted;
import com.fixup.requests.api.RepairRequestPutOnHold;
import com.fixup.requests.api.RepairRequestResumed;
import com.fixup.requests.api.RepairRequestUrgencyChanged;
import com.fixup.requests.api.SlaBreachDue;
import com.fixup.requests.api.SlaWarningDue;
import com.fixup.contracts.api.LeaseContractCancelled;
import com.fixup.contracts.api.LeaseContractCreated;
import com.fixup.contracts.api.LeaseContractRenewed;
import com.fixup.contracts.api.LeaseContractSigned;
import com.fixup.contracts.api.LeaseContractTerminated;
import com.fixup.properties.api.PropertyPublished;
import com.fixup.properties.api.PropertyBatchPublished;
import com.fixup.properties.api.PropertyUpdated;
import com.fixup.properties.api.PropertyUnlisted;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class NotificationEventListener {
    private final PublishNotification publish;

    NotificationEventListener(PublishNotification publish) {
        this.publish = publish;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestOpened(RepairRequestOpened event) {
        var title = "Nueva solicitud de reparación";
        var message = new StringBuilder()
                .append("Se abrió una solicitud de reparación para ")
                .append(humanize(event.specialty().name()))
                .append(": \"").append(trimTo(event.title(), 100)).append("\" (")
                .append(event.urgency().name().toLowerCase(Locale.ROOT)).append(").");
        publish.execute(event.ownerUserId(), NotificationType.REPAIR_REQUEST_OPENED,
                title, message.toString(), event.requestId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestAssigned(RepairRequestAssigned event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_ASSIGNED,
                "Tu reparación ya tiene técnico asignado",
                "Se asignó un técnico para atender tu solicitud de reparación.",
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.REPAIR_REQUEST_ASSIGNED,
                "Tienes una nueva reparación asignada",
                "El propietario aceptó tu cotización. Puedes comenzar cuando estés listo.",
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onProgressStarted(RepairRequestProgressStarted event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_STARTED,
                "El técnico comenzó la reparación",
                "Tu reparación ha pasado a estado EN PROGRESO.",
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.REPAIR_REQUEST_STARTED,
                "Comenzaste la reparación",
                "El trabajo ha sido marcado como EN PROGRESO.",
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestCompleted(RepairRequestCompleted event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_COMPLETED,
                "Reparación completada",
                "El técnico informa que la reparación fue completada. Puedes calificar su trabajo.",
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.REPAIR_REQUEST_COMPLETED,
                "Marcaste la reparación como completada",
                "Tu trabajo fue registrado como finalizado.",
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestOnHold(RepairRequestPutOnHold event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_ON_HOLD,
                "Tu reparación está pausada",
                "El técnico puso la reparación en espera.",
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.REPAIR_REQUEST_ON_HOLD,
                "Reparación en pausa",
                "El trabajo fue puesto en espera.",
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestResumed(RepairRequestResumed event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_RESUMED,
                "Tu reparación se reanudó",
                "El técnico retomó la reparación.",
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.REPAIR_REQUEST_RESUMED,
                "Reparación reanudada",
                "El trabajo fue reanudado desde la espera.",
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequestCancelled(RepairRequestCancelled event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_CANCELLED,
                "Cancelaste la solicitud de reparación",
                "Tu solicitud fue cancelada. Abre una nueva si necesitas retomar el trabajo.",
                event.requestId()));
        if (event.fixerUserId() != null) {
            batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                    NotificationType.REPAIR_REQUEST_CANCELLED,
                    "El propietario canceló la reparación",
                    "La solicitud de reparación asignada fue cancelada por el propietario.",
                    event.requestId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onUrgencyChanged(RepairRequestUrgencyChanged event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        var msg = new StringBuilder()
                .append("La urgencia pasó de ").append(prevName(event))
                .append(" a ").append(event.newUrgency().name().toLowerCase(Locale.ROOT)).append('.');
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.REPAIR_REQUEST_URGENCY_CHANGED,
                "Urgencia de la reparación actualizada",
                msg.toString(), event.requestId()));
        if (event.fixerUserId() != null) {
            batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                    NotificationType.REPAIR_REQUEST_URGENCY_CHANGED,
                    "Cambió la urgencia de tu reparación asignada",
                    msg.toString(), event.requestId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onQuotationSubmitted(QuotationSubmitted event) {
        publish.execute(event.ownerUserId(), NotificationType.NEW_QUOTATION,
                "Recibiste una nueva cotización",
                new StringBuilder()
                        .append("Un técnico cotizó tu reparación por ")
                        .append(formatAmount(event.amount())).append('.')
                        .toString(),
                event.requestId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onQuotationAccepted(QuotationAccepted event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.QUOTATION_ACCEPTED,
                "Confirmaste una cotización",
                new StringBuilder()
                        .append("Aceptaste la cotización por ")
                        .append(formatAmount(event.amount())).append('.')
                        .toString(),
                event.requestId()));
        batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                NotificationType.QUOTATION_ACCEPTED,
                "¡Tu cotización fue aceptada!",
                new StringBuilder()
                        .append("El propietario aceptó tu cotización por ")
                        .append(formatAmount(event.amount())).append('.')
                        .toString(),
                event.requestId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onQuotationRejected(QuotationRejected event) {
        publish.execute(event.fixerUserId(), NotificationType.QUOTATION_REJECTED,
                "Tu cotización fue rechazada",
                "El propietario rechazó tu cotización. Revisa otras solicitudes abiertas.",
                event.requestId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onContractCreated(LeaseContractCreated event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.CONTRACT_CREATED,
                "Creaste un contrato de alquiler",
                new StringBuilder()
                        .append("Tu nuevo contrato fue creado. Alquiler mensual: ")
                        .append(formatAmount(event.monthlyRent())).append('.')
                        .toString(),
                event.contractId()));
        batch.add(new PublishNotification.BatchItem(event.tenantUserId(),
                NotificationType.CONTRACT_CREATED,
                "Hay un contrato esperando tu firma",
                new StringBuilder()
                        .append("Se creó un contrato de alquiler para vos. Alquiler: ")
                        .append(formatAmount(event.monthlyRent())).append('.')
                        .toString(),
                event.contractId()));
        if (event.managerUserId() != null
                && !event.managerUserId().equals(event.ownerUserId())) {
            batch.add(new PublishNotification.BatchItem(event.managerUserId(),
                    NotificationType.CONTRACT_CREATED,
                    "Se creó un contrato bajo tu gestión",
                    "Un nuevo contrato de alquiler requiere tu seguimiento.",
                    event.contractId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onContractSigned(LeaseContractSigned event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        String who;
        if (event.signedBy().equals(event.ownerUserId())) {
            who = "El propietario";
        } else if (event.signedBy().equals(event.tenantUserId())) {
            who = "El inquilino";
        } else {
            who = "El administrador";
        }
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.CONTRACT_SIGNED,
                "El contrato recibió una firma",
                who + " firmó el contrato de alquiler.",
                event.contractId()));
        batch.add(new PublishNotification.BatchItem(event.tenantUserId(),
                NotificationType.CONTRACT_SIGNED,
                "Nueva firma en tu contrato",
                who + " firmó el contrato de alquiler.",
                event.contractId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onContractRenewed(LeaseContractRenewed event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.CONTRACT_RENEWED,
                "Tu contrato de alquiler fue renovado",
                "Se generó una nueva versión renovada del contrato de alquiler.",
                event.newContractId()));
        batch.add(new PublishNotification.BatchItem(event.tenantUserId(),
                NotificationType.CONTRACT_RENEWED,
                "Tu contrato de alquiler fue renovado",
                "El propietario renovó tu contrato de alquiler. Revisa las nuevas condiciones.",
                event.newContractId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onContractTerminated(LeaseContractTerminated event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        String reason = event.reason() == null || event.reason().isBlank()
                ? "sin motivo detallado" : ": " + trimTo(event.reason(), 120);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.CONTRACT_TERMINATED,
                "Se rescindió el contrato de alquiler",
                "El contrato fue rescindido" + reason,
                event.contractId()));
        batch.add(new PublishNotification.BatchItem(event.tenantUserId(),
                NotificationType.CONTRACT_TERMINATED,
                "Tu contrato de alquiler fue rescindido",
                "El contrato de alquiler fue rescindido" + reason,
                event.contractId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onContractCancelled(LeaseContractCancelled event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        String reason = event.reason() == null || event.reason().isBlank()
                ? "sin motivo detallado" : ": " + trimTo(event.reason(), 120);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.CONTRACT_CANCELLED,
                "El contrato de alquiler fue cancelado",
                "El contrato fue cancelado" + reason,
                event.contractId()));
        batch.add(new PublishNotification.BatchItem(event.tenantUserId(),
                NotificationType.CONTRACT_CANCELLED,
                "Tu contrato de alquiler fue cancelado",
                "El contrato de alquiler fue cancelado" + reason,
                event.contractId()));
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaWarning(SlaWarningDue event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.SLA_WARNING, event.title(), event.message(), event.requestId()));
        if (event.fixerUserId() != null) {
            batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                    NotificationType.SLA_WARNING, event.title(), event.message(), event.requestId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onSlaBreach(SlaBreachDue event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.SLA_BREACH, event.title(), event.message(), event.requestId()));
        if (event.fixerUserId() != null) {
            batch.add(new PublishNotification.BatchItem(event.fixerUserId(),
                    NotificationType.SLA_BREACH, event.title(), event.message(), event.requestId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPropertyPublished(PropertyPublished event) {
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        String title = "Inmueble publicado";
        String message = "Tu inmueble \"" + trimTo(event.title(), 80) + "\" está ahora publicado y visible para búsquedas.";
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.PROPERTY_PUBLISHED, title, message, event.propertyId()));
        if (event.managerUserId() != null && !event.managerUserId().equals(event.ownerUserId())) {
            batch.add(new PublishNotification.BatchItem(event.managerUserId(),
                    NotificationType.PROPERTY_PUBLISHED, title, message, event.propertyId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPropertyBatchPublished(PropertyBatchPublished event) {
        if (event.publishedCount() <= 0) return;
        String title = event.publishedCount() + " inmuebles publicados";
        String message = "Publicaste correctamente " + event.publishedCount() + " inmueble(s) en lote.";
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.PROPERTY_BATCH_PUBLISHED, title, message, null));
        if (event.managerUserId() != null && !event.managerUserId().equals(event.ownerUserId())) {
            batch.add(new PublishNotification.BatchItem(event.managerUserId(),
                    NotificationType.PROPERTY_BATCH_PUBLISHED, title, message, null));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPropertyUpdated(PropertyUpdated event) {
        String title = "Inmueble actualizado";
        String message = "Se actualizaron los datos de uno de tus inmuebles.";
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.PROPERTY_UPDATED, title, message, event.propertyId()));
        if (event.managerUserId() != null && !event.managerUserId().equals(event.ownerUserId())) {
            batch.add(new PublishNotification.BatchItem(event.managerUserId(),
                    NotificationType.PROPERTY_UPDATED, title, message, event.propertyId()));
        }
        publish.executeBatch(batch);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPropertyUnlisted(PropertyUnlisted event) {
        String title = "Inmueble despublicado";
        String message = "Tu inmueble \"" + trimTo(event.title(), 80) + "\" ya no está visible en búsquedas.";
        List<PublishNotification.BatchItem> batch = new ArrayList<>(2);
        batch.add(new PublishNotification.BatchItem(event.ownerUserId(),
                NotificationType.PROPERTY_UNLISTED, title, message, event.propertyId()));
        if (event.managerUserId() != null && !event.managerUserId().equals(event.ownerUserId())) {
            batch.add(new PublishNotification.BatchItem(event.managerUserId(),
                    NotificationType.PROPERTY_UNLISTED, title, message, event.propertyId()));
        }
        publish.executeBatch(batch);
    }

    private static String humanize(String name) {
        if (name == null || name.isBlank()) return "";
        var lower = name.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String formatAmount(long amount) {
        try {
            var fmt = NumberFormat.getCurrencyInstance(new Locale("es", "AR"));
            fmt.setMaximumFractionDigits(2);
            return fmt.format(java.math.BigDecimal.valueOf(amount));
        } catch (Exception ignored) {
            return "$" + amount;
        }
    }

    private static String prevName(RepairRequestUrgencyChanged e) {
        return e.previousUrgency() == null ? "(sin definir)" : e.previousUrgency().name().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unused")
    private void swallowIfNoRecipient(UUID ignored) {
    }
}
