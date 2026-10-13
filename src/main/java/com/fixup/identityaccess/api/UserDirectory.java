package com.fixup.identityaccess.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lo mínimo que otra pantalla necesita para nombrar a la contraparte: el nombre visible. Nunca
 * expone el correo, el subject externo ni los roles de otra persona.
 */
public interface UserDirectory {

    /** Nombres visibles de los usuarios pedidos; los identificadores desconocidos no aparecen. */
    Map<UUID, String> displayNames(Collection<UUID> userIds);

    default String displayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return displayNames(List.of(userId)).get(userId);
    }

    /** True solo si la cuenta existe, está ACTIVE y tiene el rol en la base (nunca en el token). */
    boolean hasActiveRole(UUID userId, Role role);

    /** Subconjunto de los usuarios dados que están ACTIVE y tienen el rol, en una sola consulta. */
    Set<UUID> activeWithRole(Collection<UUID> userIds, Role role);

    /** Cuentas activas con el rol, ordenadas por nombre. Lo usa, p. ej., quien delega en un administrador. */
    List<UserCard> activeUsersWithRole(Role role);

    record UserCard(UUID id, String displayName) {
    }
}
