package com.fixup.requests.application;

import com.fixup.jobs.api.JobCompleted;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestNotFoundException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.domain.RepairRequests;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class CompleteRequestOnJobCompleted {
    private final RepairRequests requests;

    CompleteRequestOnJobCompleted(RepairRequests requests) {
        this.requests = requests;
    }

    @EventListener
    @Order(2)
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(JobCompleted event) {
        var request = requests.findByIdForUpdate(event.requestId())
                .orElseThrow(RepairRequestNotFoundException::new);
        if (request.status() == RepairRequestStatus.COMPLETED) {
            return;
        }
        if (request.status() != RepairRequestStatus.ASSIGNED
                || !event.fixerUserId().equals(request.assignedFixerUserId())
                || !event.ownerUserId().equals(request.ownerUserId())) {
            throw new RepairRequestConflictException("REQUEST_NOT_ASSIGNED",
                    "Only an assigned request can be completed");
        }
        requests.update(request.withStatus(RepairRequestStatus.COMPLETED, event.completedAt()));
    }
}
