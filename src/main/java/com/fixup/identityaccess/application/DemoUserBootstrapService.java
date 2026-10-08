package com.fixup.identityaccess.application;

import com.fixup.identityaccess.api.DemoUserBootstrap;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.domain.ExternalIdentity;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class DemoUserBootstrapService implements DemoUserBootstrap {
    private static final Logger log = LoggerFactory.getLogger(DemoUserBootstrapService.class);
    private final UserRegistration registration;
    private final RoleAssignments assignments;

    DemoUserBootstrapService(UserRegistration registration, RoleAssignments assignments) {
        this.registration = registration;
        this.assignments = assignments;
    }

    @Override
    public Result bootstrap() {
        int created = 0;
        UUID ownerUserId = null;
        List<DemoUserSpec> users = List.of(
                new DemoUserSpec(Role.OWNER, "demo-owner@fixup.app", "Demo Propietario"),
                new DemoUserSpec(Role.TENANT, "demo-tenant@fixup.app", "Demo Arrendatario"),
                new DemoUserSpec(Role.FIXER, "demo-fixer@fixup.app", "Demo Técnico Fixer"),
                new DemoUserSpec(Role.REAL_ESTATE_MANAGER, "demo-admin@fixup.app",
                        "Demo Admin Inmobiliaria"),
                new DemoUserSpec(Role.PLATFORM_ADMIN, "demo-super@fixup.app",
                        "Demo Platform Admin")
        );
        for (DemoUserSpec spec : users) {
            BootstrapResult result = registration.register(
                    new ExternalIdentity(spec.subject(), spec.email(), spec.displayName()));
            if (result.created()) {
                created++;
                log.info("Created demo user {} ({}) -> {}", spec.primary(), spec.email(),
                        result.user().id());
            }
            if (spec.primary() == Role.OWNER) {
                ownerUserId = result.user().id();
            }
            for (Role r : spec.rolesToGrant()) {
                assignments.grant(result.user().id(), r);
            }
        }
        int granted = 0;
        BootstrapResult superOwner = registration.register(
                new ExternalIdentity("auth0|demo-superuser", "demo-superuser@fixup.app",
                        "Demo Super-User (todos los roles)"));
        if (superOwner.created()) {
            created++;
        }
        for (Role r : Role.values()) {
            assignments.grant(superOwner.user().id(), r);
            granted++;
        }
        return new Result(ownerUserId, created, granted, superOwner.user().externalSubject());
    }

    private record DemoUserSpec(Role primary, String email, String displayName) {
        String subject() {
            return "auth0|demo-" + primary.name().toLowerCase(Locale.ROOT);
        }
        Set<Role> rolesToGrant() {
            return switch (primary) {
                case OWNER -> Set.of(Role.OWNER);
                case TENANT -> Set.of(Role.TENANT);
                case FIXER -> Set.of(Role.FIXER);
                case REAL_ESTATE_MANAGER -> Set.of(Role.REAL_ESTATE_MANAGER, Role.OWNER);
                case PLATFORM_ADMIN -> Set.of(Role.PLATFORM_ADMIN, Role.REAL_ESTATE_MANAGER, Role.OWNER);
            };
        }
    }
}
