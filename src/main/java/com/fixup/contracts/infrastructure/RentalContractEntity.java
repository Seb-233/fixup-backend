package com.fixup.contracts.infrastructure;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.domain.RentalContract;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "rental_contracts")
class RentalContractEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "tenant_user_id", nullable = false)
    private UUID tenantUserId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "monthly_rent_amount", nullable = false)
    private long monthlyRentAmount;

    @Column(name = "deposit_amount", nullable = false)
    private long depositAmount;

    @Column(name = "payment_day_of_month", nullable = false)
    private int paymentDayOfMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ContractStatus status;

    @Column(name = "renewal_notice_days", nullable = false)
    private int renewalNoticeDays;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "reminder_30d_sent", nullable = false)
    private boolean reminder30dSent;

    @Column(name = "reminder_7d_sent", nullable = false)
    private boolean reminder7dSent;

    @Column(name = "reminder_expired_sent", nullable = false)
    private boolean reminderExpiredSent;

    protected RentalContractEntity() {}

    RentalContractEntity(RentalContract contract) {
        this.id = contract.id();
        this.propertyId = contract.propertyId();
        this.ownerUserId = contract.ownerUserId();
        this.tenantUserId = contract.tenantUserId();
        this.startDate = contract.startDate();
        this.endDate = contract.endDate();
        this.monthlyRentAmount = contract.monthlyRentAmount();
        this.depositAmount = contract.depositAmount();
        this.paymentDayOfMonth = contract.paymentDayOfMonth();
        this.status = contract.status();
        this.renewalNoticeDays = contract.renewalNoticeDays();
        this.notes = contract.notes();
        this.createdAt = contract.createdAt();
        this.updatedAt = contract.updatedAt();
        this.reminder30dSent = contract.reminder30dSent();
        this.reminder7dSent = contract.reminder7dSent();
        this.reminderExpiredSent = contract.reminderExpiredSent();
    }

    RentalContract toDomain() {
        return new RentalContract(
            id, propertyId, ownerUserId, tenantUserId,
            startDate, endDate, monthlyRentAmount, depositAmount,
            paymentDayOfMonth, status, renewalNoticeDays, notes,
            createdAt, updatedAt, reminder30dSent, reminder7dSent, reminderExpiredSent
        );
    }
}
