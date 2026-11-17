package com.fixup.audit.application;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.domain.StatusChangeEntry;
import com.fixup.audit.domain.StatusChanges;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListStatusChanges {
    private final StatusChanges statusChanges;
    private final UserDirectory users;

    ListStatusChanges(StatusChanges statusChanges, UserDirectory users) {
        this.statusChanges = statusChanges;
        this.users = users;
    }

    /**
     * La consulta debe correr dentro de una transaccion: RlsSessionTransactionManager publica las
     * variables de sesion (app.current_user_id/app.current_user_roles) al abrir la transaccion, y
     * sin transaccion activa la politica SELECT de V50 recibe un rol vacio y niega todas las filas.
     * Mismo limite transaccional que el resto de casos de uso de lectura del proyecto.
     */
    @Transactional(readOnly = true)
    public List<StatusChangeView> execute(com.fixup.identityaccess.api.CurrentActor actor,
            Optional<AuditedResourceType> resourceType, Optional<UUID> resourceId, int limit) {
        if (actor == null || !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new AccessDeniedException("PLATFORM_ADMIN role required");
        }
        if (resourceId.isPresent() && resourceType.isEmpty()) {
            throw new IllegalArgumentException("resourceType is required when resourceId is provided");
        }
        int sanitizedLimit = Math.max(1, Math.min(limit, 500));
        return statusChanges.findRecent(resourceType, resourceId, sanitizedLimit).stream()
                .map(this::toView)
                .toList();
    }

    private StatusChangeView toView(StatusChangeEntry entry) {
        String displayName = entry.changedByUserId() == null
                ? entry.changedBySubject()
                : java.util.Optional.ofNullable(users.displayName(entry.changedByUserId()))
                        .orElse(entry.changedBySubject());
        return new StatusChangeView(entry.id(), entry.resourceType(), entry.resourceId(),
                entry.previousStatus(), entry.newStatus(), entry.changedByUserId(),
                entry.changedBySubject(), displayName, entry.changedAt());
    }
}
