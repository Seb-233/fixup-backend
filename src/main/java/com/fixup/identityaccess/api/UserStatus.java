package com.fixup.identityaccess.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum UserStatus {
    ACTIVE, SUSPENDED, DISABLED
}
