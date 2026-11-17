package com.fixup.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.api.StatusChangeCommand;
import com.fixup.audit.domain.StatusChanges;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StatusAuditServiceTest {
    private final StatusChanges statusChanges = mock(StatusChanges.class);
    private final StatusAuditService service = new StatusAuditService(statusChanges);

    @Test
    void savesAnAuditEntryWhenTheStatusChanges() {
        UUID resourceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant changedAt = Instant.parse("2026-11-15T14:00:00Z");

        service.record(StatusChangeCommand.user(AuditedResourceType.REPAIR_REQUEST, resourceId,
                "OPEN", "ASSIGNED", userId, "auth0|owner", changedAt));

        ArgumentCaptor<com.fixup.audit.domain.StatusChangeEntry> captor =
                ArgumentCaptor.forClass(com.fixup.audit.domain.StatusChangeEntry.class);
        verify(statusChanges).save(captor.capture());
        assertThat(captor.getValue().resourceType()).isEqualTo(AuditedResourceType.REPAIR_REQUEST);
        assertThat(captor.getValue().resourceId()).isEqualTo(resourceId);
        assertThat(captor.getValue().previousStatus()).isEqualTo("OPEN");
        assertThat(captor.getValue().newStatus()).isEqualTo("ASSIGNED");
        assertThat(captor.getValue().changedByUserId()).isEqualTo(userId);
        assertThat(captor.getValue().changedBySubject()).isEqualTo("auth0|owner");
        assertThat(captor.getValue().changedAt()).isEqualTo(changedAt);
        assertThat(captor.getValue().id()).isNotNull();
    }

    @Test
    void ignoresTransitionsThatDoNotChangeTheStatus() {
        service.record(StatusChangeCommand.system(AuditedResourceType.JOB, UUID.randomUUID(),
                "ASSIGNED", "ASSIGNED", Instant.parse("2026-11-15T14:05:00Z")));

        verify(statusChanges, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
