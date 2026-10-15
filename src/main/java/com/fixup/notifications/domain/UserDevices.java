package com.fixup.notifications.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserDevices {

    void registerDevice(UUID userId, String deviceToken, String platform, Instant createdAt);

    List<UserDevice> findByUserId(UUID userId);

    void unregisterDevice(String deviceToken);

    record UserDevice(UUID id, UUID userId, String deviceToken, String platform, Instant createdAt) {
    }
}
