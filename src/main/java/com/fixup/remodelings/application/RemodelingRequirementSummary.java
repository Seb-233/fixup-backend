package com.fixup.remodelings.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.jobs.api.JobStatus;
import com.fixup.requests.api.RepairRequestStatus;
import java.util.UUID;

public record RemodelingRequirementSummary(UUID requirementId, UUID requestId, int position,
        Specialty specialty, RepairRequestStatus requestStatus, UUID assignedFixerUserId,
        UUID jobId, JobStatus jobStatus) {
}
