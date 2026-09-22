package com.fixup.requests.application;

import com.fixup.requests.api.RepairRequestUrgency;

import java.util.List;
import java.util.UUID;

/** FR-UC-08: la urgencia es opcional en el cuerpo; si no llega, la solicitud nace en MEDIUM. */
public record NewRepairRequest(UUID propertyId, String title, String description,
        List<UUID> mediaIds, RepairRequestUrgency urgency) {
}
