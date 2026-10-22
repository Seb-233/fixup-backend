package com.fixup.visits.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DIAGNOSTIC: el técnico revisa antes de que el propietario decida entre las cotizaciones.
 * EXECUTION: la visita en la que se hace el trabajo ya asignado.
 * KITCHEN_ASSESSMENT (FR-UC-27): valoración integral de cocina con checklist por frente de obra.
 */
@Schema(enumAsRef = true)
public enum VisitKind {
    DIAGNOSTIC,
    EXECUTION,
    KITCHEN_ASSESSMENT
}
