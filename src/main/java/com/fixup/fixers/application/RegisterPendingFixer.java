package com.fixup.fixers.application;

import com.fixup.fixers.domain.FixerProfile;
import com.fixup.fixers.domain.FixerProfiles;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.RoleGranted;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class RegisterPendingFixer {
    private final FixerProfiles profiles;

    RegisterPendingFixer(FixerProfiles profiles) {
        this.profiles = profiles;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(RoleGranted event) {
        if (event.role() == Role.FIXER && profiles.findByUserId(event.internalUserId()).isEmpty()) {
            profiles.create(FixerProfile.pending(event.internalUserId(), Instant.now()));
        }
    }
}
