package com.fixup.notifications.infrastructure;

import com.fixup.notifications.api.NotificationType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationJpaRepository extends JpaRepository<NotificationEntity, UUID> {

    Page<NotificationEntity> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    Page<NotificationEntity> findByRecipientUserIdAndTypeOrderByCreatedAtDesc(UUID recipientUserId, NotificationType type, Pageable pageable);

    @Query("select n from NotificationEntity n where n.recipientUserId = :userId and n.readAt is null order by n.createdAt desc")
    Page<NotificationEntity> findUnreadByRecipientUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("select n from NotificationEntity n where n.recipientUserId = :userId and n.type = :type and n.readAt is null order by n.createdAt desc")
    Page<NotificationEntity> findUnreadByRecipientUserIdAndType(@Param("userId") UUID userId, @Param("type") NotificationType type, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(UUID recipientUserId);

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :when where n.id = :id and n.recipientUserId = :userId and n.readAt is null")
    int markAsRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("when") java.time.Instant when);

    @Modifying
    @Query("update NotificationEntity n set n.readAt = :when where n.recipientUserId = :userId and n.readAt is null")
    int markAllAsRead(@Param("userId") UUID userId, @Param("when") java.time.Instant when);
}
