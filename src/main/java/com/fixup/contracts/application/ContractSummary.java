package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.api.PaymentFrequency;
import com.fixup.contracts.domain.LeaseContract;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContractSummary(
        UUID id,
        UUID propertyId,
        UUID ownerUserId,
        UUID tenantUserId,
        UUID realEstateManagerUserId,
        ContractStatus status,
        LocalDate startDate,
        LocalDate endDate,
        long monthlyRent,
        long securityDeposit,
        PaymentFrequency paymentFrequency,
        int paymentDayOfMonth,
        String currency,
        String contractTerms,
        List<UUID> mediaIds,
        String clauses,
        boolean ownerSigned,
        boolean tenantSigned,
        Instant tenantSignedAt,
        Instant ownerSignedAt,
        UUID signedByOwnerUserId,
        UUID signedByTenantUserId,
        String terminationReason,
        Instant terminatedAt,
        UUID terminatedByUserId,
        ContractStatus terminatedFromStatus,
        String cancellationReason,
        Instant cancelledAt,
        UUID cancelledByUserId,
        LocalDate renewalStartDate,
        LocalDate renewalEndDate,
        UUID originalContractId,
        Instant createdAt,
        Instant updatedAt) {

    static ContractSummary of(LeaseContract c) {
        return new ContractSummary(c.id(), c.propertyId(), c.ownerUserId(), c.tenantUserId(),
                c.realEstateManagerUserId(), c.status(), c.startDate(), c.endDate(),
                c.monthlyRent(), c.securityDeposit(), c.paymentFrequency(),
                c.paymentDayOfMonth(), c.currency(), c.contractTerms(), c.mediaIds(),
                c.clauses(), c.ownerSigned(), c.tenantSigned(), c.tenantSignedAt(),
                c.ownerSignedAt(), c.signedByOwnerUserId(), c.signedByTenantUserId(),
                c.terminationReason(), c.terminatedAt(), c.terminatedByUserId(),
                c.terminatedFromStatus(), c.cancellationReason(), c.cancelledAt(),
                c.cancelledByUserId(), c.renewalStartDate(), c.renewalEndDate(),
                c.originalContractId(), c.createdAt(), c.updatedAt());
    }

    static List<ContractSummary> of(List<LeaseContract> list) {
        return list.stream().map(ContractSummary::of).toList();
    }
}
