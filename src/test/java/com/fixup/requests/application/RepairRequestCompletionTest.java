package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.*;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.RequestInvitations;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RepairRequestCompletionTest {
    private final UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), fixer = UUID.randomUUID();
    private final Instant started = Instant.parse("2026-10-01T12:00:00Z"), done = started.plusSeconds(600);
    private final RepairRequests repository = mock(RepairRequests.class);
    private final RepairRequestAssignment service = new RepairRequestAssignment(repository, mock(RequestInvitations.class));

    @ParameterizedTest
    @EnumSource(value = RepairRequestStatus.class, names = {"ASSIGNED", "SLA_WARNING", "SLA_BREACHED"})
    void completesAssignedAndSlaStatesPreservingContext(RepairRequestStatus status) {
        var original = request(status, fixer);
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(original));
        service.complete(command(owner, fixer));
        var changed = ArgumentCaptor.forClass(RepairRequest.class);
        verify(repository).update(changed.capture());
        assertThat(changed.getValue()).isEqualTo(original.complete(done));
        assertThat(changed.getValue().completedAt()).isEqualTo(done);
    }

    @Test
    void compatibleRepeatPreservesFirstCompletionDateWithoutWriting() {
        var completed = request(RepairRequestStatus.ASSIGNED, fixer).complete(done.minusSeconds(10));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(completed));
        service.complete(command(owner, fixer));
        verify(repository, never()).update(any());
        assertThat(completed.completedAt()).isEqualTo(done.minusSeconds(10));
    }

    @Test
    void identitiesAreCheckedEvenForAlreadyCompletedRequests() {
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(request(RepairRequestStatus.ASSIGNED, fixer).complete(done)));
        assertThatThrownBy(() -> service.complete(command(UUID.randomUUID(), fixer)))
                .isInstanceOf(RepairRequestConflictException.class);
        assertThatThrownBy(() -> service.complete(command(owner, UUID.randomUUID())))
                .isInstanceOf(RepairRequestConflictException.class);
        verify(repository, never()).update(any());
    }

    @ParameterizedTest
    @EnumSource(value = RepairRequestStatus.class, names = {"OPEN", "CANCELLED"})
    void rejectsOtherStatesEvenWithAssignedIdentity(RepairRequestStatus status) {
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(request(status, fixer)));
        assertThatThrownBy(() -> service.complete(command(owner, fixer)))
                .isInstanceOf(RepairRequestConflictException.class);
        verify(repository, never()).update(any());
    }

    @Test
    void rejectsUnassignedRequests() {
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(request(RepairRequestStatus.ASSIGNED, null)));
        assertThatThrownBy(() -> service.complete(command(owner, fixer)))
                .isInstanceOf(RepairRequestConflictException.class);
        verify(repository, never()).update(any());
    }

    @Test
    void missingRequestIsNotCompleted() {
        assertThatThrownBy(() -> service.complete(command(owner, fixer)))
                .isInstanceOf(RepairRequestNotFoundException.class);
        verify(repository, never()).update(any());
    }

    private RepairRequestCompletionCommand command(UUID ownerId, UUID fixerId) {
        return new RepairRequestCompletionCommand(id, ownerId, fixerId, done);
    }

    private RepairRequest request(RepairRequestStatus status, UUID assigned) {
        return new RepairRequest(id, UUID.randomUUID(), "Bogotá", owner, Specialty.PLUMBING,
                "Fuga", "Reparar tubería", List.of(UUID.randomUUID()), status, assigned, PaymentMethod.TRANSFER,
                started, started, UrgencyLevel.URGENT, started.plusSeconds(172800), started.plusSeconds(60),
                UUID.randomUUID(), UUID.randomUUID(), "Casa", "Dirección", null);
    }
}
