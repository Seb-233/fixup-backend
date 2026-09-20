package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestUrgency;
import java.util.List;
import java.util.UUID;

public record NewRepairRequest(Specialty specialty, String title, String description,
        List<UUID> mediaIds, RepairRequestUrgency urgency) {
}
