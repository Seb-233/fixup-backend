package com.fixup.requests.infrastructure;

import com.fixup.requests.application.CheckRepairRequestSla;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FR-UC-08: dispara el barrido de SLA periódicamente. La decisión de a quién avisar y cuándo vive
 * en la capa de aplicación; aquí solo está el reloj.
 */
@Component
@ConditionalOnProperty(name = "fixup.sla.enabled", havingValue = "true", matchIfMissing = true)
class SlaCheckScheduler {
    private final CheckRepairRequestSla checkSla;

    SlaCheckScheduler(CheckRepairRequestSla checkSla) {
        this.checkSla = checkSla;
    }

    @Scheduled(initialDelayString = "${fixup.sla.initial-delay:30s}",
            fixedDelayString = "${fixup.sla.polling-delay:5m}")
    void checkActiveRequests() {
        checkSla.sweep();
    }
}
