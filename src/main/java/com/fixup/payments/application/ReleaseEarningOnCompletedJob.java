package com.fixup.payments.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.payments.domain.FixerEarnings;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: cerrar el trabajo libera el escrow. El ingreso pasa a disponible dentro de la misma
 * transacción que cierra el trabajo, de modo que no puede quedar un trabajo terminado cuyo dinero
 * siga retenido.
 */
@Component
class ReleaseEarningOnCompletedJob {
    private final FixerEarnings earnings;

    ReleaseEarningOnCompletedJob(FixerEarnings earnings) {
        this.earnings = earnings;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(JobCompleted event) {
        earnings.findByQuotationForUpdate(event.quotationId())
                .filter(earning -> !earning.isAvailable())
                .ifPresent(earning -> earnings.update(earning.release(Instant.now())));
    }
}
