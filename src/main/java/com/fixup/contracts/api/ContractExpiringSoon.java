package com.fixup.contracts.api;

import java.util.UUID;

public record ContractExpiringSoon(
    String type,
    UUID contractId,
    UUID ownerUserId,
    UUID tenantUserId
) {}
