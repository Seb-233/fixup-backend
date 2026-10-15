package com.fixup.contracts.application;

import java.time.LocalDate;
import java.util.UUID;

public record CreateContractRequest(
    UUID propertyId,
    UUID tenantUserId,
    LocalDate startDate,
    LocalDate endDate,
    long monthlyRentAmount,
    long depositAmount,
    int paymentDayOfMonth,
    String notes
) {}
