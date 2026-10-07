package com.fixup.requests.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum UrgencyLevel {
    LOW, MEDIUM, HIGH, URGENT
}
