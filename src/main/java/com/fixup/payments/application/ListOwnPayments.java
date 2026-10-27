package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.InvalidPaymentQueryException;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.domain.PaymentContexts;
import com.fixup.payments.domain.PaymentPurpose;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayments;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded recovery of payments owned by the caller or under its current property delegation. */
@Service
public class ListOwnPayments {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final RepairPayments payments;
    private final PaymentContexts contexts;

    ListOwnPayments(RepairPayments payments, PaymentContexts contexts) {
        this.payments = payments;
        this.contexts = contexts;
    }

    @Transactional(readOnly = true)
    public PaymentPage execute(CurrentActor actor, UUID requestId, PaymentStatus status,
            PaymentPurpose purpose, int page, int size) {
        if (actor.status() != UserStatus.ACTIVE
                || !(actor.hasRole(Role.OWNER) || actor.hasRole(Role.REAL_ESTATE_MANAGER))) {
            throw new PaymentAccessDeniedException();
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPaymentQueryException("page must be non-negative and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        var result = payments.findVisibleForPayer(actor.internalUserId(), requestId, status, purpose, page, size);
        var contextsByQuotation = contexts.findByQuotations(result.items().stream().map(payment -> payment.quotationId()).toList());
        return new PaymentPage(result.items().stream()
                .map(payment -> PaymentDetail.from(payment, contextsByQuotation.get(payment.quotationId())))
                .toList(), result.page(), result.size(), result.hasMore());
    }

    public record PaymentPage(List<PaymentDetail> items, int page, int size, boolean hasMore) {
        public PaymentPage { items = List.copyOf(items); }
    }
}
