package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationDocumentType;

/** The storage key of the verification document. */
public record DocumentSubmission(FixerVerificationDocumentType type, String storageKey) {
}
