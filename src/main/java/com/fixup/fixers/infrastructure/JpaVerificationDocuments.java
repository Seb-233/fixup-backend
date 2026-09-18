package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.domain.VerificationDocument;
import com.fixup.fixers.domain.VerificationDocuments;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
class JpaVerificationDocuments implements VerificationDocuments {
    private final VerificationDocumentJpaRepository repository;

    JpaVerificationDocuments(VerificationDocumentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(VerificationDocument document) {
        var id = new VerificationDocumentId(document.userId(), document.type());
        // Resubmitting the same document type replaces the stored key instead of duplicating a row.
        var entity = repository.findById(id).orElseGet(() -> VerificationDocumentEntity.from(document));
        entity.replaceWith(document);
        repository.saveAndFlush(entity);
    }

    @Override
    public Set<FixerVerificationDocumentType> typesOf(UUID userId) {
        return repository.findByUserId(userId).stream().map(entity -> entity.id().documentType())
                .collect(Collectors.toUnmodifiableSet());
    }
}
