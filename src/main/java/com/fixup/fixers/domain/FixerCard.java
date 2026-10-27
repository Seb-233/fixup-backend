package com.fixup.fixers.domain;

import java.math.BigDecimal;

/**
 * FR-UC-09 y FR-UC-16: lo que el perfil público del técnico agrega a su verificación: cómo se
 * presenta (ciudad, biografía, experiencia) y la reputación que le dieron sus clientes.
 */
public record FixerCard(FixerProfile profile, String bio, Integer yearsExperience, BigDecimal ratingAverage,
        int ratingCount) {
}
