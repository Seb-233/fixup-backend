package com.fixup.requests.application;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.SlaBreached;
import com.fixup.requests.api.SlaWarningRaised;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class SlaCheckSchedulerTest {

    @Autowired SlaCheckScheduler scheduler;
    @Autowired Clock clock;
    @MockBean RepairRequests requests;
    @Autowired ApplicationEvents events;

    private Instant baseNow;
    private UUID requestId;
    private UUID propertyId;
    private UUID ownerUserId;

    @BeforeEach
    void setUp() {
        baseNow = Instant.parse("2025-07-01T10:00:00Z");
        requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        propertyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ownerUserId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    }

    private RepairRequest urgentWithDeadline(Instant deadline, RepairRequestStatus status) {
        return new RepairRequest(
                requestId,
                propertyId,
                "Madrid",
                ownerUserId,
                Specialty.PLUMBING,
                "Gotera",
                "Gotera en el baño",
                List.of(),
                status,
                null,
                baseNow.minus(Duration.ofHours(10)),
                baseNow.minus(Duration.ofHours(10)),
                UrgencyLevel.URGENT,
                deadline,
                null);
    }

    @Test
    void requestInWarningThresholdTransitionsToSlaWarning() {
        Instant deadline = baseNow.plus(Duration.ofHours(5));
        RepairRequest open = urgentWithDeadline(deadline, RepairRequestStatus.OPEN);
        when(requests.findUrgentUnresolved()).thenReturn(List.of(open));
        when(requests.findById(requestId)).thenReturn(Optional.of(open));

        scheduler.checkSlas();

        ArgumentCaptor<RepairRequest> captor = ArgumentCaptor.forClass(RepairRequest.class);
        verify(requests, atLeastOnce()).update(captor.capture());
        RepairRequest saved = captor.getAllValues().stream()
                .filter(r -> r.status() == RepairRequestStatus.SLA_WARNING)
                .findFirst()
                .orElseThrow();
        assertThat(saved.status()).isEqualTo(RepairRequestStatus.SLA_WARNING);
    }

    @Test
    void breachedRequestTransitionsToSlaBreachedAndSetsEscalationTimestamp() {
        Instant deadline = baseNow.minus(Duration.ofHours(1));
        RepairRequest assigned = urgentWithDeadline(deadline, RepairRequestStatus.ASSIGNED);
        when(requests.findUrgentUnresolved()).thenReturn(List.of(assigned));
        when(requests.findById(requestId)).thenReturn(Optional.of(assigned));

        scheduler.checkSlas();

        ArgumentCaptor<RepairRequest> captor = ArgumentCaptor.forClass(RepairRequest.class);
        verify(requests, atLeastOnce()).update(captor.capture());
        RepairRequest saved = captor.getAllValues().stream()
                .filter(r -> r.status() == RepairRequestStatus.SLA_BREACHED)
                .findFirst()
                .orElseThrow();
        assertThat(saved.status()).isEqualTo(RepairRequestStatus.SLA_BREACHED);
        assertThat(saved.lastEscalationNotifiedAt()).isNotNull();
    }

    @Test
    void requestWithPlentyOfTimeRemainsUnchanged() {
        Instant deadline = baseNow.plus(Duration.ofHours(30));
        RepairRequest open = urgentWithDeadline(deadline, RepairRequestStatus.OPEN);
        when(requests.findUrgentUnresolved()).thenReturn(List.of(open));
        when(requests.findById(requestId)).thenReturn(Optional.of(open));

        scheduler.checkSlas();

        verify(requests, never()).update(any(RepairRequest.class));
        assertThat(open.status()).isEqualTo(RepairRequestStatus.OPEN);
        assertThat(open.updatedAt()).isEqualTo(baseNow.minus(Duration.ofHours(10)));
        assertThat(open.slaDeadline()).isEqualTo(deadline);
        assertThat(open.lastEscalationNotifiedAt()).isNull();
        assertThat(events.stream(SlaWarningRaised.class)).isEmpty();
        assertThat(events.stream(SlaBreached.class)).isEmpty();
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2025-07-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
