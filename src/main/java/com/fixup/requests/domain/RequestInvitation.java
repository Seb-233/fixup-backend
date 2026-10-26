package com.fixup.requests.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * FR-UC-11: el administrador inmobiliario invita a un técnico concreto a cotizar. La invitación
 * no asigna: el técnico sigue cotizando y el administrador sigue decidiendo entre las ofertas.
 */
public record RequestInvitation(UUID id, UUID requestId, UUID fixerUserId, UUID invitedByUserId, String note,
        Instant createdAt) {
}
