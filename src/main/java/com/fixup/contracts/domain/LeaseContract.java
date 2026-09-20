package com.fixup.contracts.domain;

import com.fixup.contracts.api.ContractConflictException;
import com.fixup.contracts.api.ContractSnapshot;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.api.PaymentFrequency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LeaseContract(
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

    public static LeaseContract createDraft(UUID id, UUID propertyId, UUID ownerUserId,
            UUID tenantUserId, UUID realEstateManagerUserId, LocalDate startDate, LocalDate endDate,
            long monthlyRent, long securityDeposit, PaymentFrequency paymentFrequency,
            int paymentDayOfMonth, String currency, String contractTerms, List<UUID> mediaIds,
            String clauses, Instant now) {
        validateDatesAndAmounts(startDate, endDate, monthlyRent, securityDeposit, paymentDayOfMonth);
        if (ownerUserId.equals(tenantUserId)) {
            throw new ContractConflictException("SAME_OWNER_AND_TENANT",
                    "Owner and tenant cannot be the same user");
        }
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                ContractStatus.DRAFT, startDate, endDate, monthlyRent, securityDeposit,
                paymentFrequency, paymentDayOfMonth, currency, contractTerms, mediaIds, clauses,
                false, false, null, null, null, null,
                null, null, null, null,
                null, null, null,
                null, null, null,
                now, now);
    }

    private static void validateDatesAndAmounts(LocalDate startDate, LocalDate endDate,
            long monthlyRent, long securityDeposit, int paymentDayOfMonth) {
        if (startDate == null) {
            throw new ContractConflictException("INVALID_DATES", "startDate is required");
        }
        if (endDate == null || !endDate.isAfter(startDate)) {
            throw new ContractConflictException("INVALID_DATES",
                    "endDate must be after startDate");
        }
        if (monthlyRent <= 0) {
            throw new ContractConflictException("INVALID_AMOUNTS", "monthlyRent must be > 0");
        }
        if (securityDeposit < 0) {
            throw new ContractConflictException("INVALID_AMOUNTS", "securityDeposit cannot be negative");
        }
        if (paymentDayOfMonth < 1 || paymentDayOfMonth > 31) {
            throw new ContractConflictException("INVALID_PAYMENT_DAY",
                    "paymentDayOfMonth must be between 1 and 31");
        }
    }

    public LeaseContract sendForTenantSignature() {
        if (status != ContractStatus.DRAFT) {
            throw new ContractConflictException("INVALID_STATUS",
                    "Can only send for signature from DRAFT; current status: " + status);
        }
        return withStatus(ContractStatus.PENDING_TENANT_SIGNATURE, Instant.now());
    }

    public LeaseContract signAsTenant(UUID tenantUserId, Instant now) {
        if (status != ContractStatus.PENDING_TENANT_SIGNATURE) {
            throw new ContractConflictException("INVALID_STATUS_FOR_TENANT_SIGN",
                    "Expected PENDING_TENANT_SIGNATURE; current: " + status);
        }
        if (!this.tenantUserId.equals(tenantUserId)) {
            throw new ContractConflictException("NOT_TENANT",
                    "Only the tenant can sign as tenant");
        }
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                ContractStatus.PENDING_OWNER_SIGNATURE, startDate, endDate, monthlyRent,
                securityDeposit, paymentFrequency, paymentDayOfMonth, currency, contractTerms,
                mediaIds, clauses, ownerSigned, true, now, ownerSignedAt,
                signedByOwnerUserId, tenantUserId,
                terminationReason, terminatedAt, terminatedByUserId, terminatedFromStatus,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public LeaseContract signAsOwner(UUID ownerUserId, Instant now) {
        if (status != ContractStatus.PENDING_OWNER_SIGNATURE) {
            throw new ContractConflictException("INVALID_STATUS_FOR_OWNER_SIGN",
                    "Expected PENDING_OWNER_SIGNATURE; current: " + status);
        }
        if (!this.ownerUserId.equals(ownerUserId)) {
            throw new ContractConflictException("NOT_OWNER",
                    "Only the owner can sign as owner");
        }
        var today = LocalDate.now();
        var effectiveStatus = (today.isEqual(startDate) || today.isAfter(startDate))
                ? ContractStatus.ACTIVE
                : ContractStatus.SIGNED;
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                effectiveStatus, startDate, endDate, monthlyRent, securityDeposit,
                paymentFrequency, paymentDayOfMonth, currency, contractTerms, mediaIds, clauses,
                true, true, tenantSignedAt, now, ownerUserId, signedByTenantUserId,
                terminationReason, terminatedAt, terminatedByUserId, terminatedFromStatus,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public LeaseContract terminateAsOwner(String reason, Instant now) {
        if (!isActiveOrSigned()) {
            throw new ContractConflictException("INVALID_STATUS_FOR_TERMINATION",
                    "Cannot terminate from status: " + status);
        }
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                ContractStatus.TERMINATED_BY_OWNER, startDate, endDate, monthlyRent,
                securityDeposit, paymentFrequency, paymentDayOfMonth, currency, contractTerms,
                mediaIds, clauses, ownerSigned, tenantSigned, tenantSignedAt, ownerSignedAt,
                signedByOwnerUserId, signedByTenantUserId,
                reason, now, ownerUserId, status,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public LeaseContract terminateAsTenant(String reason, Instant now) {
        if (!isActiveOrSigned()) {
            throw new ContractConflictException("INVALID_STATUS_FOR_TERMINATION",
                    "Cannot terminate from status: " + status);
        }
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                ContractStatus.TERMINATED_BY_TENANT, startDate, endDate, monthlyRent,
                securityDeposit, paymentFrequency, paymentDayOfMonth, currency, contractTerms,
                mediaIds, clauses, ownerSigned, tenantSigned, tenantSignedAt, ownerSignedAt,
                signedByOwnerUserId, signedByTenantUserId,
                reason, now, tenantUserId, status,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public LeaseContract cancel(String reason, UUID cancelledByUserId, Instant now) {
        if (isFinal()) {
            throw new ContractConflictException("ALREADY_FINAL",
                    "Contract is already in a final state: " + status);
        }
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                ContractStatus.CANCELLED, startDate, endDate, monthlyRent, securityDeposit,
                paymentFrequency, paymentDayOfMonth, currency, contractTerms, mediaIds, clauses,
                ownerSigned, tenantSigned, tenantSignedAt, ownerSignedAt,
                signedByOwnerUserId, signedByTenantUserId,
                terminationReason, terminatedAt, terminatedByUserId, terminatedFromStatus,
                reason, now, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public LeaseContract renew(UUID newContractId, LocalDate newStartDate, LocalDate newEndDate,
            long newMonthlyRent, long newSecurityDeposit, PaymentFrequency newPaymentFrequency,
            int newPaymentDay, Instant now) {
        if (status != ContractStatus.ACTIVE && status != ContractStatus.EXPIRED
                && status != ContractStatus.SIGNED) {
            throw new ContractConflictException("INVALID_STATUS_FOR_RENEWAL",
                    "Contract must be ACTIVE, EXPIRED or SIGNED to renew; current: " + status);
        }
        validateDatesAndAmounts(newStartDate, newEndDate, newMonthlyRent, newSecurityDeposit, newPaymentDay);
        var renewed = createDraft(newContractId, propertyId, ownerUserId, tenantUserId,
                realEstateManagerUserId, newStartDate, newEndDate, newMonthlyRent, newSecurityDeposit,
                newPaymentFrequency, newPaymentDay, currency, contractTerms, mediaIds, clauses, now);
        return new LeaseContract(renewed.id(), renewed.propertyId(), renewed.ownerUserId(),
                renewed.tenantUserId(), renewed.realEstateManagerUserId(),
                renewed.status(), renewed.startDate(), renewed.endDate(), renewed.monthlyRent(),
                renewed.securityDeposit(), renewed.paymentFrequency(), renewed.paymentDayOfMonth(),
                renewed.currency(), renewed.contractTerms(), renewed.mediaIds(), renewed.clauses(),
                renewed.ownerSigned(), renewed.tenantSigned(), renewed.tenantSignedAt(),
                renewed.ownerSignedAt(), renewed.signedByOwnerUserId(), renewed.signedByTenantUserId(),
                renewed.terminationReason(), renewed.terminatedAt(), renewed.terminatedByUserId(),
                renewed.terminatedFromStatus(), renewed.cancellationReason(), renewed.cancelledAt(),
                renewed.cancelledByUserId(),
                newStartDate, newEndDate, this.id(), // reference original contract
                renewed.createdAt(), renewed.updatedAt());
    }

    public LeaseContract activateIfStartDateReached(Instant now) {
        if (status == ContractStatus.SIGNED && (LocalDate.now().isEqual(startDate) || LocalDate.now().isAfter(startDate))) {
            return withStatus(ContractStatus.ACTIVE, now);
        }
        if (status == ContractStatus.ACTIVE && LocalDate.now().isAfter(endDate)) {
            return withStatus(ContractStatus.EXPIRED, now);
        }
        return this;
    }

    private LeaseContract withStatus(ContractStatus newStatus, Instant now) {
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                newStatus, startDate, endDate, monthlyRent, securityDeposit, paymentFrequency,
                paymentDayOfMonth, currency, contractTerms, mediaIds, clauses,
                ownerSigned, tenantSigned, tenantSignedAt, ownerSignedAt,
                signedByOwnerUserId, signedByTenantUserId,
                terminationReason, terminatedAt, terminatedByUserId, terminatedFromStatus,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, now);
    }

    public boolean isFinal() {
        return status == ContractStatus.EXPIRED
                || status == ContractStatus.TERMINATED_BY_OWNER
                || status == ContractStatus.TERMINATED_BY_TENANT
                || status == ContractStatus.CANCELLED;
    }

    public boolean isActiveOrSigned() {
        return status == ContractStatus.ACTIVE || status == ContractStatus.SIGNED;
    }

    public void requireVisibleBy(UUID userId) {
        if (!ownerUserId.equals(userId) && !tenantUserId.equals(userId)
                && !(realEstateManagerUserId != null && realEstateManagerUserId.equals(userId))) {
            throw new com.fixup.contracts.api.ContractAccessDeniedException();
        }
    }

    public ContractSnapshot snapshot() {
        return new ContractSnapshot(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                status, monthlyRent, startDate, endDate, createdAt);
    }
}
