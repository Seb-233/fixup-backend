package com.fixup.requests.application;

import com.fixup.requests.api.Specialty;
import java.util.List;

/** The photo keys are produced by the client after uploading against a signed URL. */
public record NewRepairRequest(Specialty specialty, String title, String description,
        List<String> photoKeys) {
}
