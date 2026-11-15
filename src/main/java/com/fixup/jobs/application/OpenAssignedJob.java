package com.fixup.jobs.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.JobLogEntry;
import com.fixup.jobs.domain.JobRecords;
import com.fixup.jobs.domain.Jobs;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OpenAssignedJob implements JobOpening {
    private final Jobs jobs;
    private final JobRecords records;
    private final StatusAuditTrail auditTrail;

    @Autowired
    OpenAssignedJob(Jobs jobs, JobRecords records, StatusAuditTrail auditTrail) {
        this.jobs = jobs;
        this.records = records;
        this.auditTrail = auditTrail;
    }

    OpenAssignedJob(Jobs jobs, JobRecords records) {
        this(jobs, records, command -> { });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(JobOpeningCommand command) {
        if (!"TRANSFER".equals(command.paymentMethod())) {
            throw new com.fixup.payments.api.PaymentConflictException("DIGITAL_PAYMENT_REQUIRED", "New jobs require approved digital payment");
        }
        if (jobs.existsByQuotation(command.quotationId())) {
            return;
        }
        var job = Job.assigned(UUID.randomUUID(), command.requestId(), command.quotationId(),
                command.fixerUserId(), command.ownerUserId(), command.paymentMethod(),
                command.agreedAmount(), command.occurredAt());
        jobs.create(job);
        // V37 requires the owner identifier on initial entries. This convention does not
        // identify the actor who initiated the operation; the note must not claim otherwise.
        records.log(new JobLogEntry(UUID.randomUUID(), job.id(), job.status(),
                "Trabajo asignado", job.ownerUserId(), job.createdAt()));
        auditTrail.record(new StatusChangeCommand(AuditedResourceType.JOB, job.id(), null,
                job.status().name(), command.changedByUserId(), command.changedBySubject(),
                command.occurredAt()));
    }
}
