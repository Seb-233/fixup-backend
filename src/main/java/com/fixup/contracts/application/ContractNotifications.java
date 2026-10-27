package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractCreated;
import com.fixup.contracts.api.ContractExpired;
import com.fixup.contracts.api.ContractExpiringSoon;
import com.fixup.notifications.api.NotificationRequested;
import com.fixup.notifications.api.NotificationType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** FR-UC-10 y FR-UC-14: avisa a propietario y arrendatario de los hitos de su contrato. */
@Component
class ContractNotifications {
    private final ApplicationEventPublisher events;

    ContractNotifications(ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener
    void on(ContractCreated event) {
        notifyBoth(event.ownerUserId(), event.tenantUserId(), event.contractId(), NotificationType.CONTRACT_CREATED,
                "Contrato registrado", "Se registró un contrato de arrendamiento en el que eres parte.", Map.of());
    }

    @EventListener
    void on(ContractExpiringSoon event) {
        boolean sevenDays = "7D".equals(event.type());
        int days = sevenDays ? 7 : 30;
        notifyBoth(event.ownerUserId(), event.tenantUserId(), event.contractId(),
                sevenDays ? NotificationType.CONTRACT_EXPIRING_7D : NotificationType.CONTRACT_EXPIRING_30D,
                "Contrato por vencer en " + days + " días",
                "Revisa las condiciones y decide si lo renuevas antes de la fecha de terminación.",
                Map.of("daysUntilExpiry", String.valueOf(days)));
    }

    @EventListener
    void on(ContractExpired event) {
        notifyBoth(event.ownerUserId(), event.tenantUserId(), event.contractId(), NotificationType.CONTRACT_EXPIRED,
                "Contrato vencido", "El contrato llegó a su fecha de terminación sin renovarse.", Map.of());
    }

    private void notifyBoth(UUID owner, UUID tenant, UUID contractId, NotificationType type, String title, String body,
            Map<String, String> data) {
        for (UUID recipient : List.of(owner, tenant)) {
            events.publishEvent(new NotificationRequested(recipient, type, title, body, "/contracts/" + contractId,
                    contractId, "CONTRACT", data));
        }
    }
}
