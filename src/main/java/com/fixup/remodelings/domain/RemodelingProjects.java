package com.fixup.remodelings.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemodelingProjects {
    void create(RemodelingProject project);

    void updatePlan(RemodelingProject project);

    Optional<RemodelingProject> findById(UUID id);

    List<RemodelingProject> findByOwner(UUID ownerUserId);
}
