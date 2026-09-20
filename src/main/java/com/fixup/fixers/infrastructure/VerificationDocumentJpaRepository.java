package com.fixup.fixers.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VerificationDocumentJpaRepository extends JpaRepository<VerificationDocumentEntity, VerificationDocumentId> {
    @Query("select document from VerificationDocumentEntity document where document.id.userId = :userId")
    List<VerificationDocumentEntity> findByUserId(@Param("userId") UUID userId);
}
