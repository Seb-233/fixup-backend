package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.domain.Jobs;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-20: los trabajos del técnico, que son el origen de sus ingresos. */
@Service
public class ListOwnJobs {
    private final Jobs jobs;

    ListOwnJobs(Jobs jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public List<JobSummary> execute(CurrentActor actor) {
        JobAccess.requireActiveFixer(actor);
        return jobs.findByFixer(actor.internalUserId()).stream().map(JobSummary::of).toList();
    }
}
