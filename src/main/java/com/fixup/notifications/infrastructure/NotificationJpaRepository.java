package com.fixup.notifications.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    @Query("SELECT n FROM NotificationEntity n WHERE n.userId = :userId "
            + "AND (:includeRead = true OR n.read = false) "
            + "ORDER BY n.createdAt DESC")
    List<NotificationEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") UUID userId,
            @Param("includeRead") boolean includeRead,
            org.springframework.data.domain.Pageable pageable);

    long countByUserIdAndReadIsFalse(UUID userId);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.read = true, n.readAt = :now "
            + "WHERE n.userId = :userId AND n.read = false")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") java.time.Instant now);
}
