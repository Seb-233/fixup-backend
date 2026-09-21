package com.fixup.fixers.application;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.util.UUID;

/** References a media asset the fixer already uploaded and confirmed for this document type. */
public record DocumentSubmission(FixerVerificationDocumentType type, UUID mediaId) {
}
