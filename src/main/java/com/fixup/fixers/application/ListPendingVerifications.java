package com.fixup.fixers.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-UC-16: la cola de revisión. El administrador ya no necesita que el técnico le dicte su
 * identificador: ve quién envió documentos y desde cuándo espera.
 */
@Service
public class ListPendingVerifications {
    private final FixerProfiles profiles;
    private final UserDirectory users;

    ListPendingVerifications(FixerProfiles profiles, UserDirectory users) {
        this.profiles = profiles;
        this.users = users;
    }

    @PreAuthorize("@internalAuthorization.isCurrentAdmin(#actor)")
    @Transactional(readOnly = true)
    public List<PendingVerification> execute(CurrentActor actor) {
        var pending = profiles.findUnderReview().stream()
                .filter(profile -> !profile.userId().equals(actor.internalUserId()))
                .toList();
        var names = users.displayNames(pending.stream().map(profile -> profile.userId()).toList());
        return pending.stream()
                .map(profile -> new PendingVerification(profile.userId(),
                        names.getOrDefault(profile.userId(), "Técnico de Fix&Rent"),
                        profile.specialties(), profile.submittedAt()))
                .toList();
    }

    public record PendingVerification(UUID fixerUserId, String displayName, Set<Specialty> specialties,
            Instant submittedAt) {
    }
}
