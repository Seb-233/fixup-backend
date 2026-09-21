package com.fixup.fixers.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum FixerVerificationDocumentType {
    ID_CARD, TRADE_CERTIFICATE, BACKGROUND_CHECK, INSURANCE
}
