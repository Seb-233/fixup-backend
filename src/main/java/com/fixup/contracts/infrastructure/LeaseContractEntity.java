package com.fixup.contracts.infrastructure;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.api.PaymentFrequency;
import com.fixup.contracts.domain.LeaseContract;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "lease_contracts")
class LeaseContractEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "property_id", nullable = false)
    private UUID propertyId;
    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;
    @Column(name = "tenant_user_id", nullable = false)
    private UUID tenantUserId;
    @Column(name = "real_estate_manager_user_id")
    private UUID realEstateManagerUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ContractStatus status;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Column(name = "monthly_rent", nullable = false)
    private long monthlyRent;
    @Column(name = "security_deposit", nullable = false)
    private long securityDeposit;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_frequency", nullable = false, length = 20)
    private PaymentFrequency paymentFrequency;
    @Column(name = "payment_day_of_month", nullable = false)
    private int paymentDayOfMonth;
    @Column(name = "currency", nullable = false, length = 8)
    private String currency;
    @Column(name = "contract_terms", length = 10000)
    private String contractTerms;
    @Convert(converter = MediaIdsConverter.class)
    @Column(name = "media_ids", length = 4000)
    private List<UUID> mediaIds;
    @Column(name = "clauses", length = 10000)
    private String clauses;
    @Column(name = "owner_signed", nullable = false)
    private boolean ownerSigned;
    @Column(name = "tenant_signed", nullable = false)
    private boolean tenantSigned;
    @Column(name = "tenant_signed_at")
    private Instant tenantSignedAt;
    @Column(name = "owner_signed_at")
    private Instant ownerSignedAt;
    @Column(name = "signed_by_owner_user_id")
    private UUID signedByOwnerUserId;
    @Column(name = "signed_by_tenant_user_id")
    private UUID signedByTenantUserId;
    @Column(name = "termination_reason", length = 2000)
    private String terminationReason;
    @Column(name = "terminated_at")
    private Instant terminatedAt;
    @Column(name = "terminated_by_user_id")
    private UUID terminatedByUserId;
    @Enumerated(EnumType.STRING)
    @Column(name = "terminated_from_status", length = 40)
    private ContractStatus terminatedFromStatus;
    @Column(name = "cancellation_reason", length = 2000)
    private String cancellationReason;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;
    @Column(name = "renewal_start_date")
    private LocalDate renewalStartDate;
    @Column(name = "renewal_end_date")
    private LocalDate renewalEndDate;
    @Column(name = "original_contract_id")
    private UUID originalContractId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaseContractEntity() {
    }

    static LeaseContractEntity from(LeaseContract c) {
        var e = new LeaseContractEntity();
        e.id = c.id();
        e.propertyId = c.propertyId();
        e.ownerUserId = c.ownerUserId();
        e.tenantUserId = c.tenantUserId();
        e.realEstateManagerUserId = c.realEstateManagerUserId();
        e.status = c.status();
        e.startDate = c.startDate();
        e.endDate = c.endDate();
        e.monthlyRent = c.monthlyRent();
        e.securityDeposit = c.securityDeposit();
        e.paymentFrequency = c.paymentFrequency();
        e.paymentDayOfMonth = c.paymentDayOfMonth();
        e.currency = c.currency();
        e.contractTerms = c.contractTerms();
        e.mediaIds = c.mediaIds();
        e.clauses = c.clauses();
        e.ownerSigned = c.ownerSigned();
        e.tenantSigned = c.tenantSigned();
        e.tenantSignedAt = c.tenantSignedAt();
        e.ownerSignedAt = c.ownerSignedAt();
        e.signedByOwnerUserId = c.signedByOwnerUserId();
        e.signedByTenantUserId = c.signedByTenantUserId();
        e.terminationReason = c.terminationReason();
        e.terminatedAt = c.terminatedAt();
        e.terminatedByUserId = c.terminatedByUserId();
        e.terminatedFromStatus = c.terminatedFromStatus();
        e.cancellationReason = c.cancellationReason();
        e.cancelledAt = c.cancelledAt();
        e.cancelledByUserId = c.cancelledByUserId();
        e.renewalStartDate = c.renewalStartDate();
        e.renewalEndDate = c.renewalEndDate();
        e.originalContractId = c.originalContractId();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        return e;
    }

    LeaseContract toDomain() {
        return new LeaseContract(id, propertyId, ownerUserId, tenantUserId, realEstateManagerUserId,
                status, startDate, endDate, monthlyRent, securityDeposit, paymentFrequency,
                paymentDayOfMonth, currency, contractTerms, mediaIds, clauses,
                ownerSigned, tenantSigned, tenantSignedAt, ownerSignedAt,
                signedByOwnerUserId, signedByTenantUserId,
                terminationReason, terminatedAt, terminatedByUserId, terminatedFromStatus,
                cancellationReason, cancelledAt, cancelledByUserId,
                renewalStartDate, renewalEndDate, originalContractId,
                createdAt, updatedAt);
    }
}
