package com.fixup.quotations.infrastructure;

import com.fixup.quotations.api.QuotationConflictException;
import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaQuotations implements Quotations {
    private final QuotationJpaRepository repository;

    JpaQuotations(QuotationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Quotation> findById(UUID id) {
        return repository.findById(id).map(QuotationEntity::toDomain);
    }

    @Override
    public Optional<Quotation> findByIdForUpdate(UUID id) {
        return repository.findAndLockById(id).map(QuotationEntity::toDomain);
    }

    @Override
    public boolean existsByRequestAndFixer(UUID requestId, UUID fixerUserId) {
        return repository.existsByRequestIdAndFixerUserId(requestId, fixerUserId);
    }

    @Override
    public List<Quotation> findByRequest(UUID requestId) {
        // Cheapest offer first: the owner compares prices, not arrival times.
        return repository.findByRequestIdOrderByAmountAsc(requestId).stream()
                .map(QuotationEntity::toDomain).toList();
    }

    @Override
    public List<Quotation> findByRequestForUpdate(UUID requestId) {
        return repository.findAndLockByRequestId(requestId).stream()
                .map(QuotationEntity::toDomain).toList();
    }

    @Override
    public List<Quotation> findByFixer(UUID fixerUserId) {
        return repository.findByFixerUserIdOrderByCreatedAtDesc(fixerUserId).stream()
                .map(QuotationEntity::toDomain).toList();
    }

    @Override
    public void create(Quotation quotation) {
        try {
            repository.saveAndFlush(QuotationEntity.from(quotation));
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueQuotationConstraintViolation(ex)) {
                throw new QuotationConflictException("ALREADY_QUOTED",
                        "This fixer already sent a quotation for the request");
            }
            throw ex;
        }
    }

    private boolean isUniqueQuotationConstraintViolation(DataIntegrityViolationException ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase().contains("uq_quotation_request_fixer")) {
                return true;
            }
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve) {
                if (cve.getConstraintName() != null && cve.getConstraintName().toLowerCase().contains("uq_quotation_request_fixer")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public void update(Quotation quotation) {
        var entity = repository.findAndLockById(quotation.id())
                .orElseThrow(QuotationNotFoundException::new);
        entity.apply(quotation);
        repository.saveAndFlush(entity);
    }
}
