package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Encuentra al arrendatario de un contrato nuevo por su correo exacto. Solo puede buscar quien puede
 * crear contratos, y la respuesta no dice nada más allá del nombre visible: un correo que no es de
 * un arrendatario activo responde igual que uno inexistente.
 */
@Service
public class FindTenant {
    private final CurrentActorProvider actors;
    private final UserDirectory users;

    FindTenant(CurrentActorProvider actors, UserDirectory users) {
        this.actors = actors;
        this.users = users;
    }

    public TenantCard byEmail(String email) {
        ContractAccess.requireCanCreate(actors.currentActor());
        return users.activeUserByEmail(email, Role.TENANT)
                .map(card -> new TenantCard(card.id(), card.displayName()))
                .orElseThrow(ContractNotFoundException::tenantNotFound);
    }

    public record TenantCard(UUID id, String displayName) {
    }
}
