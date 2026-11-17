package com.fixup.audit.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixup.audit.api.AuditedResourceType;
import com.fixup.audit.application.ListStatusChanges;
import com.fixup.audit.application.StatusChangeView;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.shared.security.ApiSecurityTestConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatusAuditController.class)
@ActiveProfiles("test")
@Import({ApiSecurityTestConfiguration.class, AuditErrorHandler.class})
class StatusAuditControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ListStatusChanges listStatusChanges;
    @MockBean CurrentActorProvider actors;

    private CurrentActor admin;
    private CurrentActor owner;

    @BeforeEach
    void setUp() {
        admin = new CurrentActor(UUID.randomUUID(), "auth0|admin",
                Set.of(Role.PLATFORM_ADMIN), UserStatus.ACTIVE);
        owner = new CurrentActor(UUID.randomUUID(), "auth0|owner",
                Set.of(Role.OWNER), UserStatus.ACTIVE);
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/administration/audit/status-changes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void nonAdminCannotReadTheAuditTrail() throws Exception {
        when(actors.currentActor()).thenReturn(owner);
        when(listStatusChanges.execute(eq(owner), eq(Optional.empty()), eq(Optional.empty()), eq(100)))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("PLATFORM_ADMIN role required"));

        mvc.perform(get("/administration/audit/status-changes"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void adminCanListFilteredAuditEntries() throws Exception {
        UUID resourceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(actors.currentActor()).thenReturn(admin);
        when(listStatusChanges.execute(eq(admin), eq(Optional.of(AuditedResourceType.REPAIR_REQUEST)),
                eq(Optional.of(resourceId)), eq(50))).thenReturn(List.of(
                        new StatusChangeView(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                                AuditedResourceType.REPAIR_REQUEST, resourceId, "OPEN", "ASSIGNED",
                                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                                "auth0|owner", "Owner Demo", Instant.parse("2026-11-15T14:15:00Z"))));

        mvc.perform(get("/administration/audit/status-changes")
                        .param("resourceType", "REPAIR_REQUEST")
                        .param("resourceId", resourceId.toString())
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].resourceType", is("REPAIR_REQUEST")))
                .andExpect(jsonPath("$[0].resourceId", is(resourceId.toString())))
                .andExpect(jsonPath("$[0].previousStatus", is("OPEN")))
                .andExpect(jsonPath("$[0].newStatus", is("ASSIGNED")))
                .andExpect(jsonPath("$[0].changedByDisplayName", is("Owner Demo")));
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void invalidFiltersReturnBadRequest() throws Exception {
        when(actors.currentActor()).thenReturn(admin);
        when(listStatusChanges.execute(eq(admin), eq(Optional.empty()),
                eq(Optional.of(UUID.fromString("11111111-1111-1111-1111-111111111111"))), eq(100)))
                .thenThrow(new IllegalArgumentException("resourceType is required when resourceId is provided"));

        mvc.perform(get("/administration/audit/status-changes")
                        .param("resourceId", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_REQUEST")))
                .andExpect(jsonPath("$.message",
                        is("resourceType is required when resourceId is provided")));
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void defaultsLimitToOneHundred() throws Exception {
        when(actors.currentActor()).thenReturn(admin);
        when(listStatusChanges.execute(eq(admin), eq(Optional.empty()), eq(Optional.empty()), eq(100)))
                .thenReturn(List.of());

        mvc.perform(get("/administration/audit/status-changes"))
                .andExpect(status().isOk());

        verify(listStatusChanges).execute(eq(admin), eq(Optional.empty()), eq(Optional.empty()), eq(100));
    }
}
