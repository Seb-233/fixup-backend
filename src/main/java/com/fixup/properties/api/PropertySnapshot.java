package com.fixup.properties.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PropertySnapshot(
    UUID id,
    UUID ownerUserId,
    String name,
    String address,
    String city,
    BigDecimal areaM2
) {}
