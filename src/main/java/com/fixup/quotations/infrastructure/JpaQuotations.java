package com.fixup.quotations.infrastructure;

import com.fixup.quotations.api.QuotationNotFoundException;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaQuotations implements Quotations {
    private final QuotationJpaRepository repository;

    JpaQuotations(QuotationJpaRepository repository) {
        this.repository = repository;
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
        repository.saveAndFlush(QuotationEntity.from(quotation));
    }

    @Override
    public void update(Quotation quotation) {
        var entity = repository.findAndLockById(quotation.id())
                .orElseThrow(QuotationNotFoundException::new);
        entity.apply(quotation);
        repository.saveAndFlush(entity);
    }
}
