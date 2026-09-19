package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.api.PaymentFrequency;
import com.fixup.contracts.domain.Contracts;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RenewLeaseContract {
    private final Contracts contracts;

    RenewLeaseContract(Contracts contracts) {
        this.contracts = contracts;
    }

    @Transactional
    public ContractSummary execute(CurrentActor actor, UUID contractId, RenewalData data) {
        ContractAccess.requireActiveUser(actor);
        var existing = contracts.findByIdForUpdate(contractId)
                .orElseThrow(ContractNotFoundException::new);
        var isOwnerOrManager = existing.ownerUserId().equals(actor.internalUserId())
                || (existing.realEstateManagerUserId() != null
                        && existing.realEstateManagerUserId().equals(actor.internalUserId()));
        if (!isOwnerOrManager && !actor.hasRole(com.fixup.identityaccess.api.Role.PLATFORM_ADMIN)) {
            throw new ContractAccessDeniedException();
        }
        var now = Instant.now();
        var renewed = existing.renew(UUID.randomUUID(), data.newStartDate(), data.newEndDate(),
                data.newMonthlyRent() > 0 ? data.newMonthlyRent() : existing.monthlyRent(),
                data.newSecurityDeposit() >= 0 ? data.newSecurityDeposit() : existing.securityDeposit(),
                data.newPaymentFrequency() != null ? data.newPaymentFrequency() : existing.paymentFrequency(),
                data.newPaymentDayOfMonth() > 0 ? data.newPaymentDayOfMonth() : existing.paymentDayOfMonth(),
                now);
        contracts.create(renewed);
        return ContractSummary.of(renewed);
    }

    public record RenewalData(
            @NotNull LocalDate newStartDate,
            @NotNull LocalDate newEndDate,
            @Positive long newMonthlyRent,
            @jakarta.validation.constraints.Min(0) long newSecurityDeposit,
            PaymentFrequency newPaymentFrequency,
            int newPaymentDayOfMonth) {
    }
}
