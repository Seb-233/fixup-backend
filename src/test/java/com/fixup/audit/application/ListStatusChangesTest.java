package com.fixup.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.domain.StatusChangeEntry;
import com.fixup.audit.domain.StatusChanges;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ListStatusChangesTest {
    private final StatusChanges statusChanges = mock(StatusChanges.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ListStatusChanges useCase = new ListStatusChanges(statusChanges, users);

    @Test
    void onlyPlatformAdminsCanConsultTheAuditTrail() {
        CurrentActor owner = new CurrentActor(UUID.randomUUID(), "auth0|owner",
                Set.of(Role.OWNER), UserStatus.ACTIVE);

        assertThatThrownBy(() -> useCase.execute(owner, Optional.empty(), Optional.empty(), 20))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("PLATFORM_ADMIN");
    }

    @Test
    void requiresResourceTypeWhenFilteringByResourceId() {
        CurrentActor admin = adminActor();

        assertThatThrownBy(() -> useCase.execute(admin, Optional.empty(),
                Optional.of(UUID.randomUUID()), 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resourceType");
    }

    @Test
    void sanitizesLimitAndResolvesDisplayNames() {
        UUID actorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant changedAt = Instant.parse("2026-11-15T14:10:00Z");
        when(statusChanges.findRecent(Optional.of(AuditedResourceType.REPAIR_PAYMENT),
                Optional.of(resourceId), 500)).thenReturn(List.of(
                        new StatusChangeEntry(UUID.randomUUID(), AuditedResourceType.REPAIR_PAYMENT,
                                resourceId, "PENDING", "APPROVED", actorId, "auth0|owner", changedAt),
                        new StatusChangeEntry(UUID.randomUUID(), AuditedResourceType.REPAIR_PAYMENT,
                                resourceId, "APPROVED", "APPROVED", null, "SYSTEM", changedAt.plusSeconds(30))));
        when(users.displayName(actorId)).thenReturn("Owner Demo");

        List<StatusChangeView> result = useCase.execute(adminActor(),
                Optional.of(AuditedResourceType.REPAIR_PAYMENT), Optional.of(resourceId), 999);

        verify(statusChanges).findRecent(Optional.of(AuditedResourceType.REPAIR_PAYMENT),
                Optional.of(resourceId), 500);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).changedByDisplayName()).isEqualTo("Owner Demo");
        assertThat(result.get(1).changedByDisplayName()).isEqualTo("SYSTEM");
    }

    @Test
    void clampsTheLimitToAtLeastOne() {
        when(statusChanges.findRecent(Optional.empty(), Optional.empty(), 1)).thenReturn(List.of());

        useCase.execute(adminActor(), Optional.empty(), Optional.empty(), 0);

        verify(statusChanges).findRecent(Optional.empty(), Optional.empty(), 1);
    }

    private static CurrentActor adminActor() {
        return new CurrentActor(UUID.randomUUID(), "auth0|admin",
                Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);
    }
}
