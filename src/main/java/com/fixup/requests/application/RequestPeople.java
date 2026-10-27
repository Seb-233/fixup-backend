package com.fixup.requests.application;

import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.requests.domain.RepairRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Nombres visibles de las personas que aparecen en una lista de solicitudes, en una sola consulta. */
@Service
public class RequestPeople {
    private final UserDirectory users;

    RequestPeople(UserDirectory users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> namesFor(Collection<RepairRequestSummary> requests) {
        var ids = new ArrayList<UUID>();
        for (var request : requests) {
            ids.add(request.ownerUserId());
            ids.add(request.requestedByUserId());
            ids.add(request.assignedFixerUserId());
        }
        return users.displayNames(ids);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> namesForDomain(Collection<RepairRequest> requests) {
        var ids = new ArrayList<UUID>();
        for (var request : requests) {
            ids.add(request.ownerUserId());
            ids.add(request.requestedByUserId());
            ids.add(request.assignedFixerUserId());
        }
        return users.displayNames(ids);
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> userIds) {
        return users.displayNames(userIds);
    }
}
