package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentContext;
import com.fixup.payments.domain.PaymentContexts;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.properties.api.PropertyDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Historical responsibility and current delegation are deliberately different checks. */
@Component
class PaymentAuthorization {
    private final PaymentContexts contexts;
    private final PropertyDirectory properties;

    PaymentAuthorization(PaymentContexts contexts, PropertyDirectory properties) {
        this.contexts = contexts;
        this.properties = properties;
    }

    PaymentContext context(UUID quotationId) {
        return contexts.findByQuotation(quotationId).orElse(null);
    }

    void requirePayer(CurrentActor actor, RepairPayment payment) {
        requirePayer(actor, payment.quotationId(), payment.ownerUserId());
    }

    void requirePayer(CurrentActor actor, UUID quotationId, UUID ownerId) {
        PaymentAccess.requireActivePayer(actor);
        var context = context(quotationId);
        // Legacy debt can lack property metadata; only its recorded responsible owner can pay it.
        if (context == null || context.propertyId() == null) {
            if (actor.hasRole(Role.OWNER) && ownerId.equals(actor.internalUserId())) return;
            throw new PaymentAccessDeniedException();
        }
        var property = properties.lockForFinancialDecision(context.propertyId());
        if (!context.ownerUserId().equals(ownerId)) throw new PaymentAccessDeniedException();
        if (actor.hasRole(Role.OWNER) && ownerId.equals(actor.internalUserId())) return;
        if (actor.hasRole(Role.REAL_ESTATE_MANAGER) && property.ownerUserId().equals(ownerId)
                && property.isManagedBy(actor.internalUserId())) return;
        throw new PaymentAccessDeniedException();
    }

    void requireReader(CurrentActor actor, RepairPayment payment) {
        if (actor.status() != UserStatus.ACTIVE) throw new PaymentAccessDeniedException();
        if (actor.hasRole(Role.PLATFORM_ADMIN)
                || actor.hasRole(Role.OWNER) && payment.ownerUserId().equals(actor.internalUserId())
                || actor.hasRole(Role.FIXER) && payment.fixerUserId().equals(actor.internalUserId())) return;
        var context = context(payment.quotationId());
        if (actor.hasRole(Role.REAL_ESTATE_MANAGER) && context != null && context.propertyId() != null) {
            var property = properties.require(context.propertyId());
            if (property.ownerUserId().equals(payment.ownerUserId()) && property.isManagedBy(actor.internalUserId())) return;
        }
        throw new PaymentAccessDeniedException();
    }
}
