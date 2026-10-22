package com.fixup.jobs.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobDirectory {
    /** Returns only jobs the active owner (or platform administrator) may inspect. */
    List<JobSnapshot> findVisibleByRequestIds(CurrentActor actor, Collection<UUID> requestIds);

    /** FR-UC-09: the job as ratings needs it; access rules are applied by the caller. */
    Optional<JobSnapshot> find(UUID jobId);
}
