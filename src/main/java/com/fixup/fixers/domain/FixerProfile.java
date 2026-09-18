package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record FixerProfile(UUID userId, FixerVerificationStatus verificationStatus,
        Instant createdAt, Instant updatedAt) {
    public void requireEligible(CurrentActor actor) {
        if (!userId.equals(actor.internalUserId()) || actor.status() != UserStatus.ACTIVE
                || !actor.hasRole(Role.FIXER) || verificationStatus != FixerVerificationStatus.VERIFIED) {
            throw new FixerNotEligibleException();
        }
    }
}
