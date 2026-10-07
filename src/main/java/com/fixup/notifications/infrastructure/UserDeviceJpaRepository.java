package com.fixup.notifications.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserDeviceJpaRepository extends JpaRepository<UserDeviceEntity, UUID> {

    List<UserDeviceEntity> findByUserId(UUID userId);

    Optional<UserDeviceEntity> findByDeviceToken(String deviceToken);

    void deleteByDeviceToken(String deviceToken);
}
