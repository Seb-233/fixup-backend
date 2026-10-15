package com.fixup.contracts.application;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateContractRequest(
    UUID propertyId,
    UUID tenantUserId,
    LocalDate startDate,
    LocalDate endDate,
    Long monthlyRentAmount,
    Long depositAmount,
    Integer paymentDayOfMonth,
    Integer renewalNoticeDays,
    String notes
) {}
