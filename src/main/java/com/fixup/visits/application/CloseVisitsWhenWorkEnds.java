package com.fixup.visits.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.visits.api.VisitStatus;
import com.fixup.visits.domain.Visits;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Al cerrarse el trabajo, las visitas que seguían propuestas o confirmadas para esa solicitud se
 * cancelan en la misma transacción: nadie debería confirmar ni presentarse a una visita de algo que
 * ya quedó resuelto.
 */
@Component
class CloseVisitsWhenWorkEnds {
    private final Visits visits;

    CloseVisitsWhenWorkEnds(Visits visits) {
        this.visits = visits;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(JobCompleted event) {
        if (event.requestId() == null) {
            return;
        }
        var now = Instant.now();
        visits.findByRequest(event.requestId()).stream()
                .filter(visit -> visit.status() == VisitStatus.PROPOSED || visit.status() == VisitStatus.CONFIRMED)
                .forEach(visit -> visits.update(visit.closeBecauseWorkEnded(now)));
    }
}
