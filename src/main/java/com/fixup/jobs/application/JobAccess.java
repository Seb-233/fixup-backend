package com.fixup.jobs.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.jobs.api.JobAccessDeniedException;
import com.fixup.jobs.domain.Job;
import com.fixup.requests.api.RepairRequestSnapshot;

/** Roles come from PostgreSQL through CurrentActor; never from the token or the frontend. */
final class JobAccess {
    private JobAccess() {
    }

    static void requireActiveFixer(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.FIXER)) {
            throw new JobAccessDeniedException();
        }
    }

    /**
     * FR-UC-19: el avance del trabajo lo leen el técnico que lo ejecuta, quienes tienen relación con
     * el inmueble (propietario, arrendatario que reportó, administrador) y el admin de plataforma.
     */
    static void requireCanRead(CurrentActor actor, Job job, RepairRequestSnapshot request) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new JobAccessDeniedException();
        }
        var userId = actor.internalUserId();
        if (job.fixerUserId().equals(userId) || job.ownerUserId().equals(userId)
                || actor.hasRole(Role.PLATFORM_ADMIN) || (request != null && request.isParticipant(userId))) {
            return;
        }
        throw new JobAccessDeniedException();
    }
}
