package com.fixup.properties.application;

import com.fixup.properties.api.PropertyWorkHistory;
import com.fixup.properties.domain.WorkRecord;
import com.fixup.properties.domain.WorkRecords;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-07: el historial se escribe en la misma transacción que cierra el trabajo o registra la
 * calificación. Un trabajo cerrado sin su fila en el historial no puede existir.
 */
@Service
class PropertyWorkHistoryService implements PropertyWorkHistory {
    private final WorkRecords records;

    PropertyWorkHistoryService(WorkRecords records) {
        this.records = records;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompletedWork(CompletedWork work) {
        if (work.propertyId() == null || records.existsByJob(work.jobId())) {
            return;
        }
        records.create(new WorkRecord(UUID.randomUUID(), work.propertyId(), work.jobId(), work.requestId(),
                work.specialty(), work.title(), work.fixerUserId(), work.completedAt(), null));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRating(UUID jobId, int score) {
        records.updateRating(jobId, score);
    }
}
