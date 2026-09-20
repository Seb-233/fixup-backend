package com.fixup.properties.application;

import java.math.BigDecimal;

public record NewProperty(
    String name,
    String address,
    String city,
    BigDecimal areaM2
) {}
