package com.fixup.contracts.application;

import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.domain.RentalContract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ContractResponse(
    UUID id,
    UUID propertyId,
    UUID ownerUserId,
    UUID tenantUserId,
    LocalDate startDate,
    LocalDate endDate,
    long monthlyRentAmount,
    long depositAmount,
    int paymentDayOfMonth,
    ContractStatus status,
    int renewalNoticeDays,
    String notes,
    Instant createdAt,
    Instant updatedAt
) {
    public static ContractResponse from(RentalContract contract) {
        return new ContractResponse(
            contract.id(),
            contract.propertyId(),
            contract.ownerUserId(),
            contract.tenantUserId(),
            contract.startDate(),
            contract.endDate(),
            contract.monthlyRentAmount(),
            contract.depositAmount(),
            contract.paymentDayOfMonth(),
            contract.status(),
            contract.renewalNoticeDays(),
            contract.notes(),
            contract.createdAt(),
            contract.updatedAt()
        );
    }
}
