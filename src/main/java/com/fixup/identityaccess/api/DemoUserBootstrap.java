package com.fixup.identityaccess.api;

import java.util.UUID;

/** Composition port implemented only in the local demo profile. */
public interface DemoUserBootstrap {
    Result bootstrap();

    record Result(UUID ownerUserId, int created, int granted, String superUserSubject) {}
}
