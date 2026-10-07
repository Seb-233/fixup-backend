package com.fixup.requests.web;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.shared.errors.ErrorResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlaBoardController.class)
@Import(SlaBoardControllerTest.Config.class)
class SlaBoardControllerTest {

    @Autowired MockMvc mvc;
    @MockBean RepairRequests requests;

    private UUID requestId;
    private UUID propertyId;
    private UUID ownerUserId;
    private UUID fixerUserId;
    private Instant now;
    private Instant deadline;

    @BeforeEach
    void setUp() {
        requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        propertyId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ownerUserId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        fixerUserId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        now = Instant.parse("2025-07-01T10:00:00Z");
        deadline = now.plusSeconds(3600);
    }

    private RepairRequest sample(RepairRequestStatus status) {
        return new RepairRequest(
                requestId,
                propertyId,
                "Madrid",
                ownerUserId,
                Specialty.ELECTRICAL,
                "Corte de luz",
                "Sin electricidad en el salón",
                List.of(),
                status,
                null,
                now.minusSeconds(7200),
                now.minusSeconds(7200),
                UrgencyLevel.URGENT,
                deadline,
                null);
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void listSlaRequestsReturnsPage() throws Exception {
        RepairRequest warning = sample(RepairRequestStatus.SLA_WARNING);
        RepairRequest breached = sample(RepairRequestStatus.SLA_BREACHED);
        Page<RepairRequest> page = new PageImpl<>(List.of(warning, breached), PageRequest.of(0, 20), 2);
        when(requests.findSlaBoardRequests(any())).thenReturn(page);

        mvc.perform(get("/administration/sla-board/requests")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].requestId", is(requestId.toString())))
                .andExpect(jsonPath("$.content[0].status", is("SLA_WARNING")))
                .andExpect(jsonPath("$.content[0].urgencyLevel", is("URGENT")))
                .andExpect(jsonPath("$.content[0].slaDeadline", notNullValue()));
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void reassignUpdatesFixer() throws Exception {
        RepairRequest existing = sample(RepairRequestStatus.SLA_WARNING);
        when(requests.findByIdForUpdate(eq(requestId))).thenReturn(Optional.of(existing));
        when(requests.findById(eq(requestId))).thenReturn(Optional.of(existing));

        String body = """
                {"fixerUserId": "%s"}
                """.formatted(fixerUserId);

        mvc.perform(post("/administration/sla-board/requests/{id}/reassign", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", is(requestId.toString())));

        verify(requests).update(any());
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void acknowledgeUpdatesEscalationTimestamp() throws Exception {
        RepairRequest existing = sample(RepairRequestStatus.SLA_WARNING);
        when(requests.findByIdForUpdate(eq(requestId))).thenReturn(Optional.of(existing));
        when(requests.findById(eq(requestId))).thenReturn(Optional.of(existing));

        mvc.perform(post("/administration/sla-board/requests/{id}/acknowledge", requestId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(requests).update(any());
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void nonAdminCannotListRequests() throws Exception {
        mvc.perform(get("/administration/sla-board/requests")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2025-07-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
