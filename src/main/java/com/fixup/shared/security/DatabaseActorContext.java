package com.fixup.shared.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-thread snapshot of the authenticated actor. Whoever resolves identity for the current request
 * (identityaccess) publishes it here; RlsSessionTransactionManager reads it back to activate
 * PostgreSQL's Row-Level Security session variables when a transaction starts. No business rules:
 * this only carries primitives, never a domain Role or CurrentActor type.
 */
public final class DatabaseActorContext {
    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private DatabaseActorContext() {
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Actor> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public record Actor(UUID userId, Set<String> roles) {
        public Actor {
            roles = Set.copyOf(roles);
        }
    }
}
