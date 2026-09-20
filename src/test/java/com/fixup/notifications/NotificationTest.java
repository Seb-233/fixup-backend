package com.fixup.notifications;

import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ENTITY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T15:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(30);

    @Test
    void createdNotificationIsUnread() {
        var n = Notification.create(UUID.randomUUID(), USER, NotificationType.NEW_QUOTATION,
                "Título", "Mensaje", ENTITY, NOW);
        assertThat(n.read()).isFalse();
        assertThat(n.readAt()).isNull();
        assertThat(n.createdAt()).isEqualTo(NOW);
        assertThat(n.userId()).isEqualTo(USER);
        assertThat(n.relatedEntityId()).isEqualTo(ENTITY);
    }

    @Test
    void markReadSetsReadAndReadAt() {
        var n = Notification.create(UUID.randomUUID(), USER, NotificationType.REPAIR_REQUEST_ASSIGNED,
                "T", "M", null, NOW);
        var marked = n.markRead(LATER);
        assertThat(marked.read()).isTrue();
        assertThat(marked.readAt()).isEqualTo(LATER);
    }

    @Test
    void markingReadTwiceIsIdempotent() {
        var n = Notification.create(UUID.randomUUID(), USER, NotificationType.GENERAL, "T", "M", null, NOW)
                .markRead(LATER);
        var again = n.markRead(LATER.plusSeconds(10));
        assertThat(again.readAt()).isEqualTo(LATER);
    }
}
