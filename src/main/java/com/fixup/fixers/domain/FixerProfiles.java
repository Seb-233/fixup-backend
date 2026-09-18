package com.fixup.fixers.domain;

import java.util.Optional;
import java.util.UUID;

public interface FixerProfiles {
    Optional<FixerProfile> findByUserId(UUID userId);

    /** Reads the profile for a decision, holding the row until the transaction ends. */
    Optional<FixerProfile> findByUserIdForUpdate(UUID userId);

    void create(FixerProfile profile);

    void update(FixerProfile profile);
}
