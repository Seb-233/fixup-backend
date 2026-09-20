package com.fixup.quotations.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface QuotationJpaRepository extends JpaRepository<QuotationEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quotation from QuotationEntity quotation where quotation.id = :id")
    Optional<QuotationEntity> findAndLockById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quotation from QuotationEntity quotation where quotation.requestId = :requestId")
    List<QuotationEntity> findAndLockByRequestId(@Param("requestId") UUID requestId);

    boolean existsByRequestIdAndFixerUserId(UUID requestId, UUID fixerUserId);

    List<QuotationEntity> findByRequestIdOrderByAmountAsc(UUID requestId);

    List<QuotationEntity> findByFixerUserIdOrderByCreatedAtDesc(UUID fixerUserId);
}
