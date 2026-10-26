package com.fixup.fixers.api;

import java.math.BigDecimal;
import java.util.UUID;

/** FR-UC-09: ratings recalcula el promedio y lo deja en el perfil público del técnico. */
public interface FixerReputation {
    void recordReputation(UUID fixerUserId, BigDecimal ratingAverage, int ratingCount);
}
