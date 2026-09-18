package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerReview;
import com.fixup.fixers.api.FixerVerificationConflictException;
import com.fixup.fixers.api.FixerVerificationDecided;
import com.fixup.fixers.domain.FixerProfile;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-16: only a PLATFORM_ADMIN still valid in PostgreSQL decides a verification. */
@Service
public class ReviewFixerVerification implements FixerReview {
    private final FixerProfiles profiles;
    private final ApplicationEventPublisher events;

    ReviewFixerVerification(FixerProfiles profiles, ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.events = events;
    }

    @Override
    @PreAuthorize("@internalAuthorization.isCurrentAdmin(#actor)")
    @Transactional
    public void approve(CurrentActor actor, UUID fixerUserId) {
        decide(actor, fixerUserId, (profile, now) -> profile.approve(actor.internalUserId(), now));
    }

    @Override
    @PreAuthorize("@internalAuthorization.isCurrentAdmin(#actor)")
    @Transactional
    public void reject(CurrentActor actor, UUID fixerUserId, String reason) {
        decide(actor, fixerUserId, (profile, now) -> profile.reject(actor.internalUserId(), reason, now));
    }

    private void decide(CurrentActor actor, UUID fixerUserId, BiFunction<FixerProfile, Instant, FixerProfile> decision) {
        if (fixerUserId.equals(actor.internalUserId())) {
            throw new FixerVerificationConflictException("SELF_REVIEW",
                    "An administrator cannot decide their own fixer verification");
        }
        // The state check and the write must see the same row: lock it before deciding, or two
        // concurrent reviewers both pass requireUnderReview and the second overwrites the first.
        var profile = profiles.findByUserIdForUpdate(fixerUserId)
                .orElseThrow(() -> new FixerVerificationConflictException("PROFILE_NOT_FOUND",
                        "There is no fixer profile awaiting a decision"));
        var decided = decision.apply(profile, Instant.now());
        profiles.update(decided);
        events.publishEvent(new FixerVerificationDecided(fixerUserId, decided.verificationStatus()));
    }
}
