package com.fixup.quotations.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum QuotationStatus {
    SUBMITTED, ACCEPTED, REJECTED
}
