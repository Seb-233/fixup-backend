package com.fixup.notifications.web;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.notifications.api.NotificationType;
import com.fixup.notifications.domain.Notification;
import com.fixup.notifications.domain.Notificaciones;
import com.fixup.notifications.domain.UserDevices;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationsController.class)
@Import(NotificationsControllerTest.TestConfig.class)
class NotificationsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean Notificaciones notificaciones;
    @MockBean UserDevices userDevices;
    @Autowired CurrentActorProvider actorProvider;

    private UUID userId;
    private Instant now;
    private List<Notification> fixture;

    @BeforeEach
    void setUp() {
        userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        now = Instant.parse("2025-01-01T10:00:00Z");

        Notification n1 = new Notification(
                UUID.randomUUID(), userId, NotificationType.QUOTATION_ACCEPTED,
                "Cotización aceptada", "Tu cotización fue aceptada",
                "/quotations/mine", UUID.randomUUID(), "QUOTATION",
                Map.of("k", "v"), null, now.minusSeconds(3600));
        Notification n2 = new Notification(
                UUID.randomUUID(), userId, NotificationType.CONTRACT_EXPIRING_7D,
                "Contrato por vencer", "Tu contrato vence en 7 días",
                "/contracts/abc", UUID.randomUUID(), "CONTRACT",
                null, null, now.minusSeconds(1800));
        Notification n3 = new Notification(
                UUID.randomUUID(), userId, NotificationType.CHAT_MESSAGE_RECEIVED,
                "Nuevo mensaje", "Tienes un nuevo mensaje",
                "/requests/123/messages", UUID.randomUUID(), "CHAT",
                null, now, now.minusSeconds(600));

        fixture = List.of(n1, n2, n3);
    }

    @Test
    void listMyNotificationsReturnsPage() throws Exception {
        Page<Notification> page = new PageImpl<>(fixture, PageRequest.of(0, 20), 3);
        when(notificaciones.findByRecipientUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(page);

        mvc.perform(get("/notifications/me")
                        .with(jwt().jwt(j -> j.subject("auth0|u1").claim("roles", "OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.content[0].id", notNullValue()))
                .andExpect(jsonPath("$.content[0].type", is("QUOTATION_ACCEPTED")))
                .andExpect(jsonPath("$.content[0].read", is(false)))
                .andExpect(jsonPath("$.content[2].read", is(true)));
    }

    @Test
    void unreadCountReturnsCount() throws Exception {
        when(notificaciones.countUnreadByRecipientUserId(userId)).thenReturn(2L);

        mvc.perform(get("/notifications/me/unread-count")
                        .with(jwt().jwt(j -> j.subject("auth0|u1").claim("roles", "OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasEntry("count", 2)));
    }

    @Test
    void markAsReadReturnsUpdatedNotification() throws Exception {
        UUID notifId = fixture.get(0).id();
        Notification read = new Notification(
                fixture.get(0).id(), fixture.get(0).recipientUserId(), fixture.get(0).type(),
                fixture.get(0).title(), fixture.get(0).body(), fixture.get(0).navigateTo(),
                fixture.get(0).entityId(), fixture.get(0).entityType(), fixture.get(0).data(),
                now, fixture.get(0).createdAt());

        when(notificaciones.markAsRead(notifId, userId)).thenReturn(true);
        when(notificaciones.findByIdAndRecipientUserId(notifId, userId))
                .thenReturn(java.util.Optional.of(read));

        mvc.perform(patch("/notifications/{id}/read", notifId)
                        .with(jwt().jwt(j -> j.subject("auth0|u1").claim("roles", "OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(notifId.toString())))
                .andExpect(jsonPath("$.read", is(true)))
                .andExpect(jsonPath("$.readAt", notNullValue()));
    }

    @Test
    void markAllAsReadReturnsMarkedCount() throws Exception {
        when(notificaciones.markAllAsRead(userId)).thenReturn(2);

        mvc.perform(patch("/notifications/read-all")
                        .with(jwt().jwt(j -> j.subject("auth0|u1").claim("roles", "OWNER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasEntry("marked", 2)));
    }

    @Test
    void registerDeviceTokenReturns201() throws Exception {
        String body = """
                {"deviceToken": "fcm-token-xyz", "platform": "ANDROID"}
                """;

        mvc.perform(post("/notifications/device-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt(j -> j.subject("auth0|u1").claim("roles", "OWNER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId", is(userId.toString())))
                .andExpect(jsonPath("$.platform", is("ANDROID")));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2025-01-01T10:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        CurrentActorProvider currentActorProvider(Clock clock) {
            return new CurrentActorProvider() {
                @Override
                public CurrentActor currentActor() {
                    return new CurrentActor(
                            UUID.fromString("11111111-1111-1111-1111-111111111111"),
                            "auth0|u1",
                            Set.of(Role.OWNER),
                            UserStatus.ACTIVE);
                }
            };
        }
    }
}
