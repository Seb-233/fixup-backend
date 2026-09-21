package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface VerificationDocuments {
    void save(VerificationDocument document);

    Set<FixerVerificationDocumentType> typesOf(UUID userId);

    /** Full records, including each document's media reference. For the administrative review view. */
    List<VerificationDocument> allOf(UUID userId);
}
