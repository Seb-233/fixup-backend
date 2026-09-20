package com.fixup.requests;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
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

    @Test
    void legacyRowWithNullPropertyIdCanBeRehydrated() {
        // Constructing directly via canonical constructor simulates reading a legacy row
        var legacy = new RepairRequest(UUID.randomUUID(), null, OWNER, Specialty.PLUMBING, "Legacy title",
                "Legacy desc", List.of(), com.fixup.requests.api.RepairRequestStatus.OPEN, null, NOW, NOW);
        assertThat(legacy.propertyId()).isNull();
        assertThatCode(() -> legacy.requireOwnedBy(OWNER)).doesNotThrowAnyException();
    }

    @Test
    void openingNewRequestWithNullPropertyIdIsRejected() {
        // Factory open() enforces new data integrity
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), null, OWNER, Specialty.PLUMBING, 
                "New title", "New desc", List.of(), NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("propertyId");
    }
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T15:00:00Z");

    private RepairRequest open() {
        return RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PLUMBING, "Gotera en el baÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â±o",
                "El agua cae desde el techo cuando el vecino abre la ducha.", List.of(UUID.randomUUID()), NOW);
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
        var tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(RepairRequest.MAX_PHOTOS + 1)
                .toList();
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", tooMany, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void aRequestRejectsDuplicateOrNullMediaIds() {
        UUID mediaId = UUID.randomUUID();
        var duplicates = List.of(mediaId, mediaId);
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", duplicates, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("duplicates");

        var withNull = java.util.Arrays.asList(UUID.randomUUID(), null);
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", withNull, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("null");
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
