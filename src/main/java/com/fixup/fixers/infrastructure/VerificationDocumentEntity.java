package com.fixup.fixers.infrastructure;

import com.fixup.fixers.domain.VerificationDocument;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fixer_verification_documents")
class VerificationDocumentEntity {
    @EmbeddedId
    private VerificationDocumentId id;
    @Column(name = "media_id", nullable = false)
    private UUID mediaId;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected VerificationDocumentEntity() {
    }

    static VerificationDocumentEntity from(VerificationDocument document) {
        var entity = new VerificationDocumentEntity();
        entity.id = new VerificationDocumentId(document.userId(), document.type());
        entity.mediaId = document.mediaId();
        entity.submittedAt = document.submittedAt();
        return entity;
    }

    VerificationDocumentId id() {
        return id;
    }

    void replaceWith(VerificationDocument document) {
        mediaId = document.mediaId();
        submittedAt = document.submittedAt();
    }

    VerificationDocument toDomain() {
        return new VerificationDocument(id.userId(), id.documentType(), mediaId, submittedAt);
    }
}
