package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.jobs.domain.Jobs;
import com.fixup.requests.api.RepairRequestDirectory;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-19 y FR-UC-20: los trabajos del técnico, con la solicitud y el cliente a los que pertenecen. */
@Service
public class ListOwnJobs {
    private final Jobs jobs;
    private final RepairRequestDirectory requests;
    private final UserDirectory users;

    ListOwnJobs(Jobs jobs, RepairRequestDirectory requests, UserDirectory users) {
        this.jobs = jobs;
        this.requests = requests;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<JobSummary> execute(CurrentActor actor) {
        JobAccess.requireActiveFixer(actor);
        return jobs.findByFixer(actor.internalUserId()).stream().map(JobSummary::of).toList();
    }

    @Transactional(readOnly = true)
    public List<JobCard> cards(CurrentActor actor) {
        var own = execute(actor);
        var snapshots = new HashMap<UUID, com.fixup.requests.api.RepairRequestSnapshot>();
        own.forEach(job -> snapshots.put(job.requestId(), requests.require(job.requestId())));
        var names = users.displayNames(own.stream().map(JobSummary::ownerUserId).toList());
        return own.stream().map(job -> {
            var request = snapshots.get(job.requestId());
            return new JobCard(job, request.title(), request.specialty().name(), request.propertyName(),
                    request.propertyAddress(), request.propertyCity(), names.get(job.ownerUserId()));
        }).toList();
    }

    public record JobCard(JobSummary job, String requestTitle, String specialty, String propertyName,
            String propertyAddress, String propertyCity, String ownerName) {
    }
}
