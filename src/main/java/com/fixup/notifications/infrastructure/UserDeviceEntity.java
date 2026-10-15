package com.fixup.notifications.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_devices")
class UserDeviceEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "device_token", nullable = false, length = 500)
    private String deviceToken;

    @Column(name = "platform", length = 32, updatable = false)
    private String platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserDeviceEntity() {
    }

    static UserDeviceEntity of(UUID userId, String deviceToken, String platform, Instant createdAt) {
        var entity = new UserDeviceEntity();
        entity.id = UUID.randomUUID();
        entity.userId = userId;
        entity.deviceToken = deviceToken;
        entity.platform = platform;
        entity.createdAt = createdAt;
        return entity;
    }

    UUID getId() {
        return id;
    }

    UUID getUserId() {
        return userId;
    }

    String getDeviceToken() {
        return deviceToken;
    }

    String getPlatform() {
        return platform;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
