package com.fixup.identityaccess.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum Role {
    OWNER, TENANT, FIXER, REAL_ESTATE_MANAGER, PLATFORM_ADMIN
}
