package com.fixup.notifications.infrastructure;

import com.fixup.notifications.domain.UserDevices;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaUserDevices implements UserDevices {

    private final UserDeviceJpaRepository repository;

    JpaUserDevices(UserDeviceJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void registerDevice(UUID userId, String deviceToken, String platform, Instant createdAt) {
        var existing = repository.findByDeviceToken(deviceToken);
        if (existing.isPresent()) {
            return;
        }
        repository.saveAndFlush(UserDeviceEntity.of(userId, deviceToken, platform, createdAt));
    }

    @Override
    public List<UserDevice> findByUserId(UUID userId) {
        return repository.findByUserId(userId).stream()
                .map(e -> new UserDevice(e.getId(), e.getUserId(), e.getDeviceToken(), e.getPlatform(), e.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void unregisterDevice(String deviceToken) {
        repository.deleteByDeviceToken(deviceToken);
    }
}
