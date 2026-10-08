package com.fixup.notifications.api;

import java.util.UUID;

/** Demo seed operation; no notification persistence internals are exported. */
public interface DemoNotifications {
    void seedForOwner(UUID ownerUserId);
}
