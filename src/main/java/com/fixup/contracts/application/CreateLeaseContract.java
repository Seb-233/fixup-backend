package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.api.PaymentFrequency;
import com.fixup.contracts.domain.Contracts;
import com.fixup.contracts.domain.LeaseContract;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateLeaseContract {
    private final Contracts contracts;

    CreateLeaseContract(Contracts contracts) {
        this.contracts = contracts;
    }

    @Transactional
    public ContractSummary execute(CurrentActor actor, NewContractData data) {
        ContractAccess.requireActiveUser(actor);
        ContractAccess.requireOwnerOrManager(actor);
        var now = Instant.now();
        var managerId = data.realEstateManagerUserId() != null ? data.realEstateManagerUserId()
                : (actor.hasRole(com.fixup.identityaccess.api.Role.REAL_ESTATE_MANAGER) ? actor.internalUserId() : null);
        var contract = LeaseContract.createDraft(UUID.randomUUID(), data.propertyId(),
                actor.internalUserId(), // current actor as owner (or use data.ownerUserId if system/admin)
                data.tenantUserId(), managerId,
                data.startDate(), data.endDate(), data.monthlyRent(), data.securityDeposit(),
                data.paymentFrequency() != null ? data.paymentFrequency() : PaymentFrequency.MONTHLY,
                data.paymentDayOfMonth() > 0 ? data.paymentDayOfMonth() : 1,
                data.currency() != null ? data.currency() : "ARS",
                data.contractTerms(), data.mediaIds(), data.clauses(), now);
        contracts.create(contract);
        return ContractSummary.of(contract);
    }

    public record NewContractData(
            @NotNull UUID propertyId,
            @NotNull UUID tenantUserId,
            UUID realEstateManagerUserId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Positive long monthlyRent,
            @jakarta.validation.constraints.Min(0) long securityDeposit,
            PaymentFrequency paymentFrequency,
            int paymentDayOfMonth,
            @Size(max = 8) String currency,
            @Size(max = 10000) String contractTerms,
            List<@NotNull UUID> mediaIds,
            @Size(max = 10000) String clauses) {
    }
}
