package com.fixup.fixers.infrastructure;

import com.fixup.fixers.domain.VerificationDocument;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "fixer_verification_documents")
class VerificationDocumentEntity {
    @EmbeddedId
    private VerificationDocumentId id;
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected VerificationDocumentEntity() {
    }

    static VerificationDocumentEntity from(VerificationDocument document) {
        var entity = new VerificationDocumentEntity();
        entity.id = new VerificationDocumentId(document.userId(), document.type());
        entity.storageKey = document.storageKey();
        entity.submittedAt = document.submittedAt();
        return entity;
    }

    VerificationDocumentId id() {
        return id;
    }

    void replaceWith(VerificationDocument document) {
        storageKey = document.storageKey();
        submittedAt = document.submittedAt();
    }
}
