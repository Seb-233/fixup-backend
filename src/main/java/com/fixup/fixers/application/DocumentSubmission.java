package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationDocumentType;

/** The storage key produced by the client after uploading against a signed URL. */
public record DocumentSubmission(FixerVerificationDocumentType type, String storageKey) {
}
