package com.fixup.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-UC-25: traceability for access blocked by the isolation controls (PostgreSQL Row-Level Security
 * or the application-layer checks that back it while a row is still visible). A single structured
 * WARN line per blocked attempt, so it can be alerted on or correlated without parsing free text.
 *
 * <p>Because RLS makes "does not exist" and "exists but is not yours" indistinguishable by design
 * (that is what keeps a stranger from learning a resource is even there), {@code reason} says which
 * kind of denial was observed, not whether the resource was real. Never logs request/response bodies,
 * tokens or any business data: only the actor, the endpoint and the denial reason.
 */
public final class SecurityAuditLog {
    private static final Logger LOG = LoggerFactory.getLogger("com.fixup.security.audit");

    private SecurityAuditLog() {
    }

    public static void accessBlocked(String reason, String httpMethod, String requestUri) {
        var actor = DatabaseActorContext.current();
        LOG.warn("blocked_access reason={} method={} uri={} actorUserId={} actorRoles={}",
                reason, httpMethod, requestUri,
                actor.map(a -> a.userId().toString()).orElse("unauthenticated"),
                actor.map(a -> String.join(",", a.roles())).orElse(""));
    }
}
