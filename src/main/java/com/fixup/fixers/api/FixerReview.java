package com.fixup.fixers.api;

import com.fixup.identityaccess.api.CurrentActor;
import java.util.UUID;

/** Administrative use case; method security rechecks the caller's database privileges. */
public interface FixerReview {
    void approve(CurrentActor actor, UUID fixerUserId);

    void reject(CurrentActor actor, UUID fixerUserId, String reason);
}
