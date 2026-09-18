package com.fixup.fixers.domain;

import java.util.Optional;
import java.util.UUID;

public interface FixerProfiles {
    Optional<FixerProfile> findByUserId(UUID userId);
    void create(FixerProfile profile);
}
