package com.fixup.jobs.application;

import com.fixup.jobs.api.JobOpening;
import com.fixup.jobs.api.JobOpeningCommand;
import com.fixup.jobs.domain.Job;
import com.fixup.jobs.domain.Jobs;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OpenAssignedJob implements JobOpening {
    private final Jobs jobs;

    OpenAssignedJob(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(JobOpeningCommand command) {
        if (jobs.existsByQuotation(command.quotationId())) {
            return;
        }
        jobs.create(Job.assigned(UUID.randomUUID(), command.requestId(), command.quotationId(),
                command.fixerUserId(), command.ownerUserId(), command.occurredAt()));
    }
}
