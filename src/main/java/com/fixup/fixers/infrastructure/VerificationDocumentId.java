package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
class VerificationDocumentId implements Serializable {
    private static final long serialVersionUID = 1L;

    @Column(name = "user_id")
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 32)
    private FixerVerificationDocumentType documentType;

    protected VerificationDocumentId() {
    }

    VerificationDocumentId(UUID userId, FixerVerificationDocumentType documentType) {
        this.userId = userId;
        this.documentType = documentType;
    }

    UUID userId() {
        return userId;
    }

    FixerVerificationDocumentType documentType() {
        return documentType;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerificationDocumentId id && Objects.equals(userId, id.userId)
                && documentType == id.documentType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, documentType);
    }
}
