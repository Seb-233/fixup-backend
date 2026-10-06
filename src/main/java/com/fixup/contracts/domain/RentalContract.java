package com.fixup.contracts.domain;

import com.fixup.contracts.api.ContractAccessDeniedException;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public class RentalContract {
    private final UUID id;
    private UUID propertyId;
    private UUID ownerUserId;
    private UUID tenantUserId;
    private LocalDate startDate;
    private LocalDate endDate;
    private long monthlyRentAmount;
    private long depositAmount;
    private int paymentDayOfMonth;
    private ContractStatus status;
    private int renewalNoticeDays;
    private String notes;
    private final Instant createdAt;
    private Instant updatedAt;
    private boolean reminder30dSent;
    private boolean reminder7dSent;
    private boolean reminderExpiredSent;

    public RentalContract(UUID id, UUID propertyId, UUID ownerUserId, UUID tenantUserId,
                          LocalDate startDate, LocalDate endDate, long monthlyRentAmount,
                          long depositAmount, int paymentDayOfMonth, ContractStatus status,
                          int renewalNoticeDays, String notes, Instant createdAt, Instant updatedAt,
                          boolean reminder30dSent, boolean reminder7dSent, boolean reminderExpiredSent) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.propertyId = Objects.requireNonNull(propertyId, "propertyId must not be null");
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        this.tenantUserId = Objects.requireNonNull(tenantUserId, "tenantUserId must not be null");
        this.startDate = Objects.requireNonNull(startDate, "startDate must not be null");
        this.endDate = Objects.requireNonNull(endDate, "endDate must not be null");
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("endDate must be after startDate");
        }
        if (monthlyRentAmount <= 0) {
            throw new IllegalArgumentException("monthlyRentAmount must be strictly positive");
        }
        if (depositAmount < 0) {
            throw new IllegalArgumentException("depositAmount must not be negative");
        }
        if (paymentDayOfMonth < 1 || paymentDayOfMonth > 28) {
            throw new IllegalArgumentException("paymentDayOfMonth must be between 1 and 28");
        }
        if (renewalNoticeDays < 0) {
            throw new IllegalArgumentException("renewalNoticeDays must not be negative");
        }
        this.monthlyRentAmount = monthlyRentAmount;
        this.depositAmount = depositAmount;
        this.paymentDayOfMonth = paymentDayOfMonth;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.renewalNoticeDays = renewalNoticeDays;
        this.notes = notes;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.reminder30dSent = reminder30dSent;
        this.reminder7dSent = reminder7dSent;
        this.reminderExpiredSent = reminderExpiredSent;
    }

    public static RentalContract create(UUID propertyId, UUID ownerUserId, UUID tenantUserId,
                                        LocalDate startDate, LocalDate endDate, long monthlyRentAmount,
                                        long depositAmount, int paymentDayOfMonth, String notes) {
        Instant now = Instant.now();
        return new RentalContract(
            UUID.randomUUID(), propertyId, ownerUserId, tenantUserId,
            startDate, endDate, monthlyRentAmount, depositAmount,
            paymentDayOfMonth, ContractStatus.ACTIVE, 30, notes,
            now, now, false, false, false
        );
    }

    public RentalContract renew(LocalDate newEndDate) {
        if (status != ContractStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE contracts can be renewed");
        }
        if (!newEndDate.isAfter(this.endDate)) {
            throw new IllegalArgumentException("newEndDate must be after current endDate");
        }
        this.endDate = newEndDate;
        this.status = ContractStatus.RENEWED;
        this.updatedAt = Instant.now();
        this.reminder30dSent = false;
        this.reminder7dSent = false;
        this.reminderExpiredSent = false;
        return this;
    }

    public RentalContract terminate(String reason) {
        if (status != ContractStatus.ACTIVE && status != ContractStatus.RENEWED) {
            throw new IllegalStateException("Only ACTIVE or RENEWED contracts can be terminated");
        }
        this.status = ContractStatus.TERMINATED;
        if (notes == null || notes.isEmpty()) {
            this.notes = reason;
        } else {
            this.notes = notes + "; Termination: " + reason;
        }
        this.updatedAt = Instant.now();
        return this;
    }

    public void requireVisibleTo(CurrentActor actor) {
        if (actor.hasRole(Role.PLATFORM_ADMIN) || actor.hasRole(Role.REAL_ESTATE_MANAGER)) {
            return;
        }
        if (actor.hasRole(Role.OWNER) && ownerUserId.equals(actor.internalUserId())) {
            return;
        }
        if (actor.hasRole(Role.TENANT) && tenantUserId.equals(actor.internalUserId())) {
            return;
        }
        throw new ContractAccessDeniedException("You do not have access to this contract");
    }

    public void markExpired() {
        this.status = ContractStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public void markReminder30dSent() {
        this.reminder30dSent = true;
        this.updatedAt = Instant.now();
    }

    public void markReminder7dSent() {
        this.reminder7dSent = true;
        this.updatedAt = Instant.now();
    }

    public void markReminderExpiredSent() {
        this.reminderExpiredSent = true;
        this.updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID propertyId() { return propertyId; }
    public UUID ownerUserId() { return ownerUserId; }
    public UUID tenantUserId() { return tenantUserId; }
    public LocalDate startDate() { return startDate; }
    public LocalDate endDate() { return endDate; }
    public long monthlyRentAmount() { return monthlyRentAmount; }
    public long depositAmount() { return depositAmount; }
    public int paymentDayOfMonth() { return paymentDayOfMonth; }
    public ContractStatus status() { return status; }
    public int renewalNoticeDays() { return renewalNoticeDays; }
    public String notes() { return notes; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public boolean reminder30dSent() { return reminder30dSent; }
    public boolean reminder7dSent() { return reminder7dSent; }
    public boolean reminderExpiredSent() { return reminderExpiredSent; }

    public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; this.updatedAt = Instant.now(); }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; this.updatedAt = Instant.now(); }
    public void setTenantUserId(UUID tenantUserId) { this.tenantUserId = tenantUserId; this.updatedAt = Instant.now(); }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; this.updatedAt = Instant.now(); }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; this.updatedAt = Instant.now(); }
    public void setMonthlyRentAmount(long monthlyRentAmount) { this.monthlyRentAmount = monthlyRentAmount; this.updatedAt = Instant.now(); }
    public void setDepositAmount(long depositAmount) { this.depositAmount = depositAmount; this.updatedAt = Instant.now(); }
    public void setPaymentDayOfMonth(int paymentDayOfMonth) { this.paymentDayOfMonth = paymentDayOfMonth; this.updatedAt = Instant.now(); }
    public void setStatus(ContractStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public void setRenewalNoticeDays(int renewalNoticeDays) { this.renewalNoticeDays = renewalNoticeDays; this.updatedAt = Instant.now(); }
    public void setNotes(String notes) { this.notes = notes; this.updatedAt = Instant.now(); }
}
