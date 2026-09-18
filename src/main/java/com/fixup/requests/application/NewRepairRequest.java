package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import java.util.List;

/** The photo keys are provisional storage keys. */
public record NewRepairRequest(Specialty specialty, String title, String description,
        List<String> photoKeys) {
}
