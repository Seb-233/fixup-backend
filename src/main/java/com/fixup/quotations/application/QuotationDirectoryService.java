package com.fixup.quotations.application;

import com.fixup.quotations.api.QuotationDirectory;
import com.fixup.quotations.domain.Quotation;
import com.fixup.quotations.domain.Quotations;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class QuotationDirectoryService implements QuotationDirectory {
    private final Quotations quotations;

    QuotationDirectoryService(Quotations quotations) {
        this.quotations = quotations;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> fixersWhoQuoted(UUID requestId) {
        return quotations.findByRequest(requestId).stream().map(Quotation::fixerUserId).collect(Collectors.toSet());
    }
}
