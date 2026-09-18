package com.fixup.requests;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-UC-18: ciclo de vida y visibilidad de la solicitud, sin contexto de Spring. */
class RepairRequestTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T15:00:00Z");

    private RepairRequest open() {
        return RepairRequest.open(UUID.randomUUID(), OWNER, Specialty.PLUMBING, "Gotera en el baño",
                "El agua cae desde el techo cuando el vecino abre la ducha.", List.of("photo/1.jpg"), NOW);
    }

    private CurrentActor actor(UUID userId, UserStatus status, Role... roles) {
        return new CurrentActor(userId, "auth0|" + userId, java.util.Set.of(roles), status);
    }

    @Test
    void newRequestStartsOpenWithoutFixer() {
        var request = open();
        assertThat(request.status()).isEqualTo(RepairRequestStatus.OPEN);
        assertThat(request.assignedFixerUserId()).isNull();
        assertThat(request.isOpen()).isTrue();
    }

    @Test
    void assigningClosesTheRequestAgainstOneFixer() {
        var assigned = open().assign(FIXER, NOW);
        assertThat(assigned.status()).isEqualTo(RepairRequestStatus.ASSIGNED);
        assertThat(assigned.assignedFixerUserId()).isEqualTo(FIXER);
        assertThat(assigned.isOpen()).isFalse();
    }

    @Test
    void assigningTwiceIsRejected() {
        var assigned = open().assign(FIXER, NOW);
        assertThatThrownBy(() -> assigned.assign(STRANGER, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void theOwnerCannotBeAssignedAsHisOwnFixer() {
        assertThatThrownBy(() -> open().assign(OWNER, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("cannot be assigned");
    }

    @Test
    void aRequestDoesNotAcceptMorePhotosThanTheLimit() {
        var tooMany = java.util.Collections.nCopies(RepairRequest.MAX_PHOTOS + 1, "photo/x.jpg");
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", tooMany, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void theOwnerAlwaysReadsHisOwnRequest() {
        assertThatCode(() -> open().requireVisibleTo(actor(OWNER, UserStatus.ACTIVE, Role.OWNER)))
                .doesNotThrowAnyException();
    }

    @Test
    void anyFixerReadsTheRequestWhileItIsOnOffer() {
        assertThatCode(() -> open().requireVisibleTo(actor(FIXER, UserStatus.ACTIVE, Role.FIXER)))
                .doesNotThrowAnyException();
    }

    @Test
    void platformAdminReadsTheRequest() {
        assertThatCode(() -> open().requireVisibleTo(actor(STRANGER, UserStatus.ACTIVE, Role.PLATFORM_ADMIN)))
                .doesNotThrowAnyException();
    }

    @Test
    void onceAssignedOnlyTheChosenFixerKeepsReadingIt() {
        var assigned = open().assign(FIXER, NOW);
        assertThatCode(() -> assigned.requireVisibleTo(actor(FIXER, UserStatus.ACTIVE, Role.FIXER)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> assigned.requireVisibleTo(actor(STRANGER, UserStatus.ACTIVE, Role.FIXER)))
                .isInstanceOf(RepairRequestAccessDeniedException.class);
    }

    @Test
    void aStrangerWithoutTheFixerRoleNeverReadsIt() {
        assertThatThrownBy(() -> open().requireVisibleTo(actor(STRANGER, UserStatus.ACTIVE, Role.TENANT)))
                .isInstanceOf(RepairRequestAccessDeniedException.class);
    }

    @Test
    void aSuspendedAccountReadsNothing() {
        assertThatThrownBy(() -> open().requireVisibleTo(actor(OWNER, UserStatus.SUSPENDED, Role.OWNER)))
                .isInstanceOf(RepairRequestAccessDeniedException.class);
    }

    @Test
    void theOwnerCheckTravelsWithTheSnapshot() {
        var snapshot = open().snapshot();
        assertThatCode(() -> snapshot.requireOwnedBy(OWNER)).doesNotThrowAnyException();
        assertThatThrownBy(() -> snapshot.requireOwnedBy(STRANGER))
                .isInstanceOf(RepairRequestAccessDeniedException.class);
        assertThat(snapshot.isOpen()).isTrue();
    }
}
