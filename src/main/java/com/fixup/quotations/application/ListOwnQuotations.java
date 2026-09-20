package com.fixup.quotations.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.quotations.domain.Quotations;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-18: las cotizaciones que el Fixer envió y en qué quedaron. */
@Service
public class ListOwnQuotations {
    private final Quotations quotations;

    ListOwnQuotations(Quotations quotations) {
        this.quotations = quotations;
    }

    @Transactional(readOnly = true)
    public List<QuotationSummary> execute(CurrentActor actor) {
        QuotationAccess.requireActiveFixer(actor);
        return quotations.findByFixer(actor.internalUserId()).stream().map(QuotationSummary::of).toList();
    }
}
