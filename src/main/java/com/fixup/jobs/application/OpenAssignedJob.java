package com.fixup.jobs.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-20: abre el trabajo dentro de la transacción que lo origina (la aceptación de una cotización
 * CASH o la aprobación del pago TRANSFER) y deja la primera entrada de su bitácora.
 */
@Service
class OpenAssignedJob implements JobOpening {
    private final Jobs jobs;
    private final JobRecords records;

    OpenAssignedJob(Jobs jobs, JobRecords records) {
        this.jobs = jobs;
        this.records = records;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(JobOpeningCommand command) {
        if (jobs.existsByQuotation(command.quotationId())) {
            return;
        }
        var job = Job.assigned(UUID.randomUUID(), command.requestId(), command.quotationId(),
                command.fixerUserId(), command.ownerUserId(), command.paymentMethod(),
                command.agreedAmount(), command.occurredAt());
        jobs.create(job);
        var origin = "CASH".equals(command.paymentMethod())
                ? "Cotización aceptada por $" + command.agreedAmount() + ", pago en efectivo"
                : "Pago por transferencia aprobado por $" + command.agreedAmount();
        records.log(new JobLogEntry(UUID.randomUUID(), job.id(), job.status(), origin,
                command.ownerUserId(), job.createdAt()));
    }
}
