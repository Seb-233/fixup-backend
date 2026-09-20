package com.fixup.fixers.domain;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import java.util.Set;
import java.util.UUID;

public interface VerificationDocuments {
    void save(VerificationDocument document);

    Set<FixerVerificationDocumentType> typesOf(UUID userId);
}
