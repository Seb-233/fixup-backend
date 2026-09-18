package com.fixup.quotations.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Quotations {
    /** Reads the quotation for a decision, holding the row until the transaction ends. */
    Optional<Quotation> findByIdForUpdate(UUID id);

    boolean existsByRequestAndFixer(UUID requestId, UUID fixerUserId);

    List<Quotation> findByRequest(UUID requestId);

    /** Reads every quotation of a request and locks them: the acceptance closes all of them. */
    List<Quotation> findByRequestForUpdate(UUID requestId);

    List<Quotation> findByFixer(UUID fixerUserId);

    void create(Quotation quotation);

    void update(Quotation quotation);
}
