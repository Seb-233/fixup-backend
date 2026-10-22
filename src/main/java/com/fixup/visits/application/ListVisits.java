package com.fixup.visits.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.visits.api.VisitAccessDeniedException;
import com.fixup.visits.api.VisitNotFoundException;
import com.fixup.visits.domain.Visit;
import com.fixup.visits.domain.Visits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-03: la agenda de visitas de cada participante, con los nombres de la contraparte. */
@Service
public class ListVisits {
    private final Visits visits;
    private final UserDirectory users;

    ListVisits(Visits visits, UserDirectory users) {
        this.visits = visits;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Agenda mine(CurrentActor actor) {
        requireActive(actor);
        return agenda(visits.findByParticipant(actor.internalUserId()));
    }

    @Transactional(readOnly = true)
    public Agenda forRequest(CurrentActor actor, UUID requestId) {
        requireActive(actor);
        var found = visits.findByRequest(requestId).stream()
                .filter(visit -> visit.isParticipant(actor.internalUserId()) || actor.hasRole(Role.PLATFORM_ADMIN))
                .toList();
        return agenda(found);
    }

    @Transactional(readOnly = true)
    public Agenda one(CurrentActor actor, UUID visitId) {
        requireActive(actor);
        var visit = visits.findById(visitId).orElseThrow(VisitNotFoundException::new);
        if (!visit.isParticipant(actor.internalUserId()) && !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new VisitNotFoundException();
        }
        return agenda(List.of(visit));
    }

    private Agenda agenda(List<Visit> found) {
        var people = new ArrayList<UUID>();
        found.forEach(visit -> {
            people.add(visit.fixerUserId());
            people.add(visit.ownerUserId());
            people.add(visit.proposedByUserId());
        });
        return new Agenda(found, users.displayNames(people));
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new VisitAccessDeniedException();
        }
    }

    public record Agenda(List<Visit> visits, Map<UUID, String> names) {
    }
}
