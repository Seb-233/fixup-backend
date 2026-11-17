package com.fixup.audit.application;

import com.fixup.audit.api.StatusAuditTrail;
import com.fixup.audit.api.StatusChangeCommand;
import com.fixup.audit.domain.StatusChangeEntry;
import com.fixup.audit.domain.StatusChanges;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class StatusAuditService implements StatusAuditTrail {
    private final StatusChanges statusChanges;

    StatusAuditService(StatusChanges statusChanges) {
        this.statusChanges = statusChanges;
    }

    @Override
    public void record(StatusChangeCommand command) {
        if (java.util.Objects.equals(command.previousStatus(), command.newStatus())) {
            return;
        }
        statusChanges.save(new StatusChangeEntry(UUID.randomUUID(), command.resourceType(),
                command.resourceId(), command.previousStatus(), command.newStatus(),
                command.changedByUserId(), command.changedBySubject(), command.changedAt()));
    }
}
