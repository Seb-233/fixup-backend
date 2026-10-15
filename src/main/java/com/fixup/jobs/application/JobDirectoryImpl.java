package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.api.JobDirectory;
import com.fixup.jobs.api.JobSnapshot;
import com.fixup.jobs.domain.Jobs;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobDirectoryImpl implements JobDirectory {
    private final Jobs jobs;

    JobDirectoryImpl(Jobs jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSnapshot> findVisibleByRequestIds(CurrentActor actor,
            Collection<UUID> requestIds) {
        if (actor.status() != UserStatus.ACTIVE
                || (!actor.hasRole(Role.OWNER) && !actor.hasRole(Role.PLATFORM_ADMIN))) {
            throw new JobAccessDeniedException();
        }
        return jobs.findByRequestIds(requestIds).stream()
                .filter(job -> actor.hasRole(Role.PLATFORM_ADMIN)
                        || job.ownerUserId().equals(actor.internalUserId()))
                .map(job -> new JobSnapshot(job.requestId(), job.id(), job.fixerUserId(),
                        job.status()))
                .toList();
    }
}
