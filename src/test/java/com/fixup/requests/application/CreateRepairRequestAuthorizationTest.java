package com.fixup.requests.application;

import com.fixup.contracts.api.TenancyDirectory;
import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.*;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.notifications.api.*;
import com.fixup.payments.api.OwnerDebtDirectory;
import com.fixup.properties.api.*;
import com.fixup.requests.api.*;
import com.fixup.requests.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateRepairRequestAuthorizationTest {
    private static final UUID PROPERTY = UUID.randomUUID(), OWNER = UUID.randomUUID(),
            TENANT = UUID.randomUUID(), MANAGER = UUID.randomUUID(), PHOTO = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-24T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RepairRequests requests = mock(RepairRequests.class);
    private final PropertyDirectory properties = mock(PropertyDirectory.class);
    private final TenancyDirectory tenancy = mock(TenancyDirectory.class);
    private final MediaAttachmentService media = mock(MediaAttachmentService.class);
    private final RequestNotifications notifications = mock(RequestNotifications.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OwnerDebtDirectory debts = mock(OwnerDebtDirectory.class);
    private final CreateRepairRequest service = new CreateRepairRequest(requests, media, properties, tenancy,
            new RepairSpecialtyClassifier(), notifications, events, debts, CLOCK);

    private static CurrentActor actor(Role role) {
        UUID id = switch (role) { case OWNER -> OWNER; case TENANT -> TENANT; default -> MANAGER; };
        return new CurrentActor(id, "synthetic", Set.of(role), UserStatus.ACTIVE);
    }

    private void property(boolean validTenant) {
        when(properties.require(PROPERTY)).thenReturn(new PropertySnapshot(PROPERTY, OWNER, "Casa", "Calle 10",
                "Bogota", BigDecimal.valueOf(80), null, null, MANAGER, null, PropertyStatus.PUBLISHED));
        when(tenancy.isActiveTenant(TENANT, PROPERTY, LocalDate.of(2026, 10, 23))).thenReturn(validTenant);
    }

    private static NewRepairRequest draft(PaymentMethod method) {
        return new NewRepairRequest(PROPERTY, " Tuberia rota ", " Fuga de agua ", List.of(PHOTO), UrgencyLevel.URGENT, method);
    }

    static Stream<Arguments> permittedRolesAndMethods() {
        return Stream.of(Role.OWNER, Role.TENANT, Role.REAL_ESTATE_MANAGER)
                .map(role -> Arguments.of(role, PaymentMethod.TRANSFER));
    }

    @Test void internalCommandsRejectCashAndExplicitNullButDefaultOmissionToTransfer() {
        assertThatThrownBy(() -> draft(PaymentMethod.CASH)).isInstanceOf(RepairRequestConflictException.class);
        assertThatThrownBy(() -> draft(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NewProjectRepairRequest(PROPERTY,"Title","Description",List.of(),UrgencyLevel.MEDIUM,PaymentMethod.CASH))
                .isInstanceOf(RepairRequestConflictException.class);
        assertThat(new NewRepairRequest(PROPERTY,"Title","Description",List.of(),UrgencyLevel.MEDIUM).paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(new NewProjectRepairRequest(PROPERTY,"Title","Description",List.of(),UrgencyLevel.MEDIUM).paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        verifyNoInteractions(requests, properties, debts);
    }

    @ParameterizedTest
    @MethodSource("permittedRolesAndMethods")
    void authorizesCanonicalOwnerAndPreservesContextAndNotifications(Role role, PaymentMethod method) {
        property(true);
        var actor = actor(role);
        when(debts.hasOutstandingDebtForProperty(PROPERTY, OWNER)).thenReturn(false);
        var summary = service.execute(actor, draft(method));
        var captured = ArgumentCaptor.forClass(RepairRequest.class);
        verify(requests).create(captured.capture());
        var request = captured.getValue();
        assertThat(request.ownerUserId()).isEqualTo(OWNER);
        assertThat(request.requestedByUserId()).isEqualTo(actor.internalUserId());
        assertThat(request.managerUserId()).isEqualTo(MANAGER);
        assertThat(request.propertyId()).isEqualTo(PROPERTY);
        assertThat(request.propertyName()).isEqualTo("Casa");
        assertThat(request.propertyAddress()).isEqualTo("Calle 10");
        assertThat(request.propertyCity()).isEqualTo("Bogota");
        assertThat(request.paymentMethod()).isEqualTo(method);
        assertThat(summary.paymentMethod()).isEqualTo(method);
        assertThat(summary.requestedByUserId()).isEqualTo(actor.internalUserId());
        assertThat(request.title()).isEqualTo("Tuberia rota");
        assertThat(request.description()).isEqualTo("Fuga de agua");
        assertThat(request.specialty()).isEqualTo(Specialty.PLUMBING);
        assertThat(request.mediaIds()).containsExactly(PHOTO);
        assertThat(request.createdAt()).isEqualTo(NOW);
        assertThat(request.slaDeadline()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        verify(media).attachRepairRequestPhotos(actor.internalUserId(), List.of(PHOTO));
        verify(notifications).urgentRequestCreated(request.id(), OWNER, request.title(), NOW);
        var notices = ArgumentCaptor.forClass(Object.class);
        verify(events, times(role == Role.TENANT ? 2 : 1)).publishEvent(notices.capture());
        var recipients = notices.getAllValues().stream().map(NotificationRequested.class::cast).toList();
        assertThat(recipients).extracting(NotificationRequested::recipientUserId)
                .containsExactlyInAnyOrderElementsOf(role == Role.TENANT ? List.of(OWNER, MANAGER)
                        : role == Role.OWNER ? List.of(MANAGER) : List.of(OWNER));
        assertThat(recipients).allSatisfy(n -> {
            assertThat(n.type()).isEqualTo(NotificationType.REQUEST_REPORTED);
            assertThat(n.entityId()).isEqualTo(request.id());
        });
        var order = inOrder(properties, debts, media, requests);
        order.verify(properties).require(PROPERTY);
        order.verify(debts).hasOutstandingDebtForProperty(PROPERTY, OWNER);
        order.verify(media).attachRepairRequestPhotos(actor.internalUserId(), List.of(PHOTO));
        order.verify(requests).create(request);
        verify(debts, never()).hasOutstandingDebt(any());
        if (role == Role.TENANT) verify(tenancy).isActiveTenant(TENANT, PROPERTY, LocalDate.of(2026, 10, 23));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "TENANT", "REAL_ESTATE_MANAGER"})
    void outstandingOwnerDebtBlocksEveryReporterBeforeAnyWrite(Role role) {
        property(true);
        when(debts.hasOutstandingDebtForProperty(PROPERTY, OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.execute(actor(role), draft(PaymentMethod.TRANSFER)))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessage("Pay outstanding debt before creating another repair request");
        verifyNoInteractions(requests, media, notifications, events);
        verify(debts, never()).hasOutstandingDebt(any());
    }

    @Test void unknownDebtResultFailsBeforeWrites() {
        property(true);
        when(debts.hasOutstandingDebtForProperty(PROPERTY, OWNER)).thenThrow(new IllegalStateException("check unavailable"));
        assertThatThrownBy(() -> service.execute(actor(Role.TENANT), draft(PaymentMethod.TRANSFER)))
                .isInstanceOf(IllegalStateException.class).hasMessage("check unavailable");
        verifyNoInteractions(requests, media, notifications, events);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "TENANT", "REAL_ESTATE_MANAGER"})
    void unrelatedActorCannotProbeDebt(Role role) {
        property(false);
        var stranger = new CurrentActor(UUID.randomUUID(), "stranger", Set.of(role), UserStatus.ACTIVE);
        assertThatThrownBy(() -> service.execute(stranger, draft(PaymentMethod.TRANSFER))).isInstanceOf(PropertyNotFoundException.class);
        verifyNoInteractions(debts, requests, media, notifications, events);
    }

    @Test void tenantWithNoCurrentContractCannotProbeDebt() {
        property(false);
        assertThatThrownBy(() -> service.execute(actor(Role.TENANT), draft(PaymentMethod.TRANSFER))).isInstanceOf(PropertyNotFoundException.class);
        verifyNoInteractions(debts, requests, media, notifications, events);
    }

    @Test void inactiveOwnerCannotProbePropertyOrDebt() {
        assertThatThrownBy(() -> service.execute(new CurrentActor(OWNER, "owner", Set.of(Role.OWNER), UserStatus.SUSPENDED),
                draft(PaymentMethod.TRANSFER))).isInstanceOf(RepairRequestAccessDeniedException.class);
        verifyNoInteractions(properties, debts, requests, media, notifications, events);
    }

    @Test void missingPaymentMethodIsNeverInvented() {
        assertThatThrownBy(() -> new NewRepairRequest(PROPERTY, "Title", "Description", List.of(), null, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(properties, debts, requests, media, notifications, events);
    }

    @Test void photoAuthorizationFailurePreventsCreationAndNotices() {
        property(true);
        doThrow(new IllegalArgumentException("photo denied")).when(media).attachRepairRequestPhotos(TENANT, List.of(PHOTO));
        assertThatThrownBy(() -> service.execute(actor(Role.TENANT), draft(PaymentMethod.TRANSFER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("photo denied");
        verifyNoInteractions(requests, notifications, events);
    }

    @Test void projectEntryPointCarriesMethodAndCanonicalOwner() {
        property(true);
        var result = service.open(actor(Role.OWNER), new NewProjectRepairRequest(PROPERTY, "Tuberia rota", "Fuga de agua",
                List.of(), UrgencyLevel.MEDIUM, PaymentMethod.TRANSFER));
        assertThat(result.paymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
        var captured = ArgumentCaptor.forClass(RepairRequest.class);
        verify(requests).create(captured.capture());
        assertThat(captured.getValue().ownerUserId()).isEqualTo(OWNER);
        verifyNoInteractions(notifications);
    }
}
