package com.fixup.requests;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.requests.api.RepairRequestAccessDeniedException;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.fixers.api.Specialty;
import com.fixup.requests.domain.RepairRequest;
import java.time.Duration;
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
                "Legacy desc", List.of(), com.fixup.requests.api.RepairRequestStatus.OPEN, null, null, null, NOW, NOW);
        assertThat(legacy.propertyId()).isNull();
        assertThatCode(() -> legacy.requireOwnedBy(OWNER)).doesNotThrowAnyException();
    }

    @Test
    void openingNewRequestWithNullPropertyIdIsRejected() {
        // Factory open() enforces new data integrity
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), null, OWNER, Specialty.PLUMBING, 
                "New title", "New desc", List.of(), null, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("propertyId");
    }
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FIXER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-18T15:00:00Z");

    private RepairRequest open() {
        return RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PLUMBING, "Gotera en el baño",
                "El agua cae desde el techo cuando el vecino abre la ducha.", List.of(UUID.randomUUID()),
                RepairRequestUrgency.MEDIUM, NOW);
    }

    private RepairRequest openWithUrgency(RepairRequestUrgency urgency) {
        return RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PLUMBING, "Gotera",
                "Descripción", List.of(), urgency, NOW);
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
                "Pintura", "Repintar la sala", tooMany, RepairRequestUrgency.MEDIUM, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("at most");
    }

    @Test
    void aRequestRejectsDuplicateOrNullMediaIds() {
        UUID mediaId = UUID.randomUUID();
        var duplicates = List.of(mediaId, mediaId);
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", duplicates, RepairRequestUrgency.MEDIUM, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("duplicates");

        var withNull = java.util.Arrays.asList(UUID.randomUUID(), null);
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PAINTING,
                "Pintura", "Repintar la sala", withNull, RepairRequestUrgency.MEDIUM, NOW))
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
        // La fusión conserva las dos cosas: el fixer asignado, que messaging necesita, y la
        // urgencia, que notifications necesita para redactar el aviso.
        assertThat(snapshot.assignedFixerUserId()).isNull();
        assertThat(snapshot.urgency()).isEqualTo(RepairRequestUrgency.MEDIUM);
        assertThat(open().assign(FIXER, NOW).snapshot().assignedFixerUserId()).isEqualTo(FIXER);
    }

    // ---------- FR-UC-08: urgencia, plazo de SLA y ciclo de vida ----------

    @Test
    void defaultUrgencyIsMediumWhenNull() {
        var request = RepairRequest.open(UUID.randomUUID(), UUID.randomUUID(), OWNER, Specialty.PLUMBING, "T", "D",
                List.of(), null, NOW);
        assertThat(request.urgency()).isEqualTo(RepairRequestUrgency.MEDIUM);
    }

    @Test
    void slaDeadlineMatchesUrgencyWindow() {
        assertThat(Duration.between(NOW, openWithUrgency(RepairRequestUrgency.LOW).slaDeadline()))
                .isEqualTo(Duration.ofDays(7));
        assertThat(Duration.between(NOW, openWithUrgency(RepairRequestUrgency.URGENT).slaDeadline()))
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    void assignedFixerMayStartProgress() {
        var inProgress = open().assign(FIXER, NOW).startProgress(NOW.plusSeconds(1));
        assertThat(inProgress.status()).isEqualTo(RepairRequestStatus.IN_PROGRESS);
    }

    @Test
    void startingProgressFromOpenIsRejected() {
        assertThatThrownBy(() -> open().startProgress(NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("started from ASSIGNED");
    }

    /**
     * El cierre lo dispara JobCompleted, y el técnico puede cerrar el trabajo sin haber pasado por
     * /start. Por eso completar se admite desde cualquier estado activo y no solo desde
     * IN_PROGRESS: si se exigiera IN_PROGRESS, cerrar el trabajo de una solicitud todavía en
     * ASSIGNED liberaría el dinero y dejaría la solicitud asignada para siempre, que es
     * exactamente lo que la puerta única quiere evitar.
     */
    @Test
    void anyActiveRequestMayBeCompleted() {
        assertThat(open().assign(FIXER, NOW).complete(NOW).status())
                .isEqualTo(RepairRequestStatus.COMPLETED);
        assertThat(open().assign(FIXER, NOW).startProgress(NOW).complete(NOW).status())
                .isEqualTo(RepairRequestStatus.COMPLETED);
        assertThat(open().assign(FIXER, NOW).putOnHold(NOW).complete(NOW).isTerminal()).isTrue();
    }

    @Test
    void completingARequestNobodyIsWorkingOnIsRejected() {
        assertThatThrownBy(() -> open().complete(NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("can be completed");
        var completed = open().assign(FIXER, NOW).startProgress(NOW).complete(NOW);
        assertThatThrownBy(() -> completed.complete(NOW))
                .isInstanceOf(RepairRequestConflictException.class);
    }

    @Test
    void assignedAndInProgressMayBePutOnHold() {
        assertThat(open().assign(FIXER, NOW).putOnHold(NOW).status())
                .isEqualTo(RepairRequestStatus.ON_HOLD);
        assertThat(open().assign(FIXER, NOW).startProgress(NOW).putOnHold(NOW).status())
                .isEqualTo(RepairRequestStatus.ON_HOLD);
    }

    @Test
    void puttingOnHoldFromOpenIsRejected() {
        assertThatThrownBy(() -> open().putOnHold(NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("applied from ASSIGNED");
    }

    @Test
    void onHoldMayResumeToInProgress() {
        var resumed = open().assign(FIXER, NOW).putOnHold(NOW).resumeFromHold(NOW);
        assertThat(resumed.status()).isEqualTo(RepairRequestStatus.IN_PROGRESS);
    }

    @Test
    void resumingFromInProgressIsRejected() {
        assertThatThrownBy(() -> open().assign(FIXER, NOW).startProgress(NOW).resumeFromHold(NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("from ON_HOLD");
    }

    @Test
    void ownerMayCancelOpenRequest() {
        var cancelled = open().cancel(NOW, OWNER);
        assertThat(cancelled.status()).isEqualTo(RepairRequestStatus.CANCELLED);
        assertThat(cancelled.assignedFixerUserId()).isNull();
        assertThat(cancelled.isTerminal()).isTrue();
    }

    @Test
    void ownerMayCancelAssignedRequestKeepingFixerReference() {
        var cancelled = open().assign(FIXER, NOW).cancel(NOW, OWNER);
        assertThat(cancelled.status()).isEqualTo(RepairRequestStatus.CANCELLED);
        assertThat(cancelled.assignedFixerUserId()).isEqualTo(FIXER);
    }

    @Test
    void cancellingAsStrangerIsForbidden() {
        assertThatThrownBy(() -> open().cancel(NOW, STRANGER))
                .isInstanceOf(RepairRequestAccessDeniedException.class);
    }

    @Test
    void cancellingCompletedRequestIsRejected() {
        var completed = open().assign(FIXER, NOW).startProgress(NOW).complete(NOW);
        assertThatThrownBy(() -> completed.cancel(NOW, OWNER))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("cannot be cancelled again");
    }

    /** El plazo se mide desde la apertura, no desde el cambio: el compromiso es con el usuario. */
    @Test
    void ownerMayChangeUrgencyAndSlaIsRecalculatedFromCreation() {
        var changed = openWithUrgency(RepairRequestUrgency.MEDIUM)
                .withUrgency(RepairRequestUrgency.HIGH, NOW.plusSeconds(1));
        assertThat(changed.urgency()).isEqualTo(RepairRequestUrgency.HIGH);
        assertThat(changed.slaDeadline()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void changingUrgencyOnCompletedRequestIsRejected() {
        var completed = open().assign(FIXER, NOW).startProgress(NOW).complete(NOW);
        assertThatThrownBy(() -> completed.withUrgency(RepairRequestUrgency.HIGH, NOW))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("changed on a completed");
    }
}
