package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.notifications.api.PushNotificationException;
import com.fixup.notifications.api.PushNotificationGateway;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FR-UC-24. Reused unchanged against H2 and PostgreSQL to keep the HTTP/security contract equivalent.
 */
abstract class ChatHttpContract {

    /** Lets a single test flip push delivery to failing without touching any real provider. */
    static final class ControllablePushGateway implements PushNotificationGateway {
        static final AtomicBoolean DOWN = new AtomicBoolean(false);

        @Override
        public com.fixup.notifications.api.NotificationStatus send(PushNotification notification) {
            if (DOWN.get()) {
                throw new PushNotificationException("Simulated FCM outage");
            }
            return com.fixup.notifications.api.NotificationStatus.SENT;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllablePushGatewayConfiguration {
        @Bean
        @Primary
        PushNotificationGateway controllablePushGateway() {
            return new ControllablePushGateway();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearIsolatedTestDatabase() {
        SecurityContextHolder.clearContext();
        databaseCleaner.clean();
        ControllablePushGateway.DOWN.set(false);
    }

    // ---------- helpers ----------

    RequestPostProcessor identity(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("email", subject + "@example.test")
                .claim("name", "Synthetic User"));
    }

    UUID provision(String subject) throws Exception {
        var result = mvc.perform(post("/auth/bootstrap").with(identity(subject)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    UUID provisionOwner(String subject) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk());
        return id;
    }

    UUID provisionVerifiedFixer(String subject, String specialty) throws Exception {
        UUID id = provision(subject);
        mvc.perform(post("/auth/select-role").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"FIXER\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE fixer_profiles SET verification_status = 'VERIFIED' WHERE user_id = ?", id);
        jdbc.update("INSERT INTO fixer_specialties (fixer_user_id, specialty) VALUES (?, ?)", id, specialty);
        return id;
    }

    UUID provisionAdmin(String subject) throws Exception {
        UUID id = provision(subject);
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'PLATFORM_ADMIN')", id);
        return id;
    }

    UUID createOpenRequest(String ownerSubject, String specialty) throws Exception {
        String body = """
                {"specialty":"%s","title":"Fuga en la cocina","description":"Fuga urgente bajo el lavaplatos","mediaIds":[]}
                """.formatted(specialty);
        var result = mvc.perform(post("/requests").with(identity(ownerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("requestId").asText());
    }

    /** Creates a request as the owner, submits one quotation from the fixer, and accepts it. */
    UUID createAssignedRequest(String ownerSubject, String fixerSubject, String specialty) throws Exception {
        UUID requestId = createOpenRequest(ownerSubject, specialty);

        String quoteBody = """
                {"requestId":"%s","amount":150000,"estimatedDays":2,"message":"Puedo ir hoy"}
                """.formatted(requestId);
        var quoteResult = mvc.perform(post("/quotations").with(identity(fixerSubject))
                .contentType(MediaType.APPLICATION_JSON).content(quoteBody))
                .andExpect(status().isCreated()).andReturn();
        UUID quotationId = UUID.fromString(mapper.readTree(quoteResult.getResponse().getContentAsString())
                .get("id").asText());

        mvc.perform(post("/quotations/" + quotationId + "/accept").with(identity(ownerSubject)))
                .andExpect(status().isOk());

        return requestId;
    }

    ResultActions send(String subject, UUID requestId, String body) throws Exception {
        return mvc.perform(post("/requests/" + requestId + "/messages").with(identity(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions list(String subject, UUID requestId) throws Exception {
        return mvc.perform(get("/requests/" + requestId + "/messages").with(identity(subject)));
    }

    String messageBody(String text) {
        return "{\"body\":\"" + text + "\"}";
    }

    // ---------- autenticación ----------

    @Test
    void anonymousRequestsToChatRoutesReturn401() throws Exception {
        var anyId = UUID.randomUUID();
        for (var request : List.of(get("/requests/" + anyId + "/messages"),
                post("/requests/" + anyId + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(messageBody("hola")))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    // ---------- los dos participantes ----------

    @Test
    void bothParticipantsCanSendAndReadTheChat() throws Exception {
        String ownerSubject = "auth0|chat-owner";
        String fixerSubject = "auth0|chat-fixer";
        UUID ownerId = provisionOwner(ownerSubject);
        UUID fixerId = provisionVerifiedFixer(fixerSubject, "PLUMBING");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "PLUMBING");

        send(ownerSubject, requestId, messageBody("Hola, cuando puedes venir?"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderUserId").value(ownerId.toString()))
                .andExpect(jsonPath("$.body").value("Hola, cuando puedes venir?"))
                .andExpect(jsonPath("$.sentAt").isNotEmpty())
                .andExpect(jsonPath("$.notificationStatus").value("SENT"));

        send(fixerSubject, requestId, messageBody("Llego en 30 minutos"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderUserId").value(fixerId.toString()));

        list(ownerSubject, requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].senderUserId").value(ownerId.toString()))
                .andExpect(jsonPath("$[0].body").value("Hola, cuando puedes venir?"))
                .andExpect(jsonPath("$[1].senderUserId").value(fixerId.toString()))
                .andExpect(jsonPath("$[1].body").value("Llego en 30 minutos"));

        // The fixer reads the exact same thread; the conversation is not one-sided.
        list(fixerSubject, requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(2);
    }

    // ---------- terceros ajenos ----------

    @Test
    void aStrangerCannotReadOrSendMessages() throws Exception {
        String ownerSubject = "auth0|chat-owner-priv";
        String fixerSubject = "auth0|chat-fixer-priv";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "ELECTRICAL");
        send(ownerSubject, requestId, messageBody("Mensaje privado")).andExpect(status().isCreated());

        provision("auth0|chat-stranger");
        list("auth0|chat-stranger", requestId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        send("auth0|chat-stranger", requestId, messageBody("Puedo ver esto?"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void anUnrelatedFixerCannotReadOrSendMessages() throws Exception {
        String ownerSubject = "auth0|chat-owner-other-fixer";
        String assignedFixerSubject = "auth0|chat-fixer-assigned";
        String otherFixerSubject = "auth0|chat-fixer-other";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(assignedFixerSubject, "PAINTING");
        provisionVerifiedFixer(otherFixerSubject, "PAINTING");
        UUID requestId = createAssignedRequest(ownerSubject, assignedFixerSubject, "PAINTING");

        list(otherFixerSubject, requestId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        send(otherFixerSubject, requestId, messageBody("Yo tambien cotice"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void platformAdminCanReadForModerationButCannotPostAsAParticipant() throws Exception {
        String ownerSubject = "auth0|chat-owner-admin";
        String fixerSubject = "auth0|chat-fixer-admin";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(fixerSubject, "MASONRY");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "MASONRY");
        send(ownerSubject, requestId, messageBody("Hola")).andExpect(status().isCreated());

        provisionAdmin("auth0|chat-admin");
        list("auth0|chat-admin", requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        send("auth0|chat-admin", requestId, messageBody("Soy soporte"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------- el chat solo existe sobre una solicitud activa ----------

    @Test
    void chatCannotBeUsedBeforeTheRequestIsAssigned() throws Exception {
        String ownerSubject = "auth0|chat-owner-open";
        provisionOwner(ownerSubject);
        UUID requestId = createOpenRequest(ownerSubject, "GENERAL");

        send(ownerSubject, requestId, messageBody("Hay alguien?"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_ASSIGNED"));

        list(ownerSubject, requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages", Integer.class)).isZero();
    }

    @Test
    void chatOnANonExistentRequestReturnsNotFound() throws Exception {
        provisionOwner("auth0|chat-owner-404");
        mvc.perform(get("/requests/" + UUID.randomUUID() + "/messages").with(identity("auth0|chat-owner-404")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    // ---------- validación de la solicitud ----------

    @Test
    void blankOrOversizedMessageBodyIsRejected() throws Exception {
        String ownerSubject = "auth0|chat-owner-invalid";
        String fixerSubject = "auth0|chat-fixer-invalid";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(fixerSubject, "CARPENTRY");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "CARPENTRY");

        send(ownerSubject, requestId, "{\"body\":\"\"}").andExpect(status().isBadRequest());
        send(ownerSubject, requestId, "{\"body\":\"   \"}").andExpect(status().isBadRequest());
        send(ownerSubject, requestId, "{}").andExpect(status().isBadRequest());
        send(ownerSubject, requestId, "{\"body\":\"x\",\"senderUserId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());

        String tooLong = "{\"body\":\"" + "a".repeat(2001) + "\"}";
        send(ownerSubject, requestId, tooLong).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE request_id = ?",
                Integer.class, requestId)).isZero();
    }

    // ---------- graceful degradation de las notificaciones push ----------

    @Test
    void pushNotificationFailureDoesNotBlockSendingOrPersistence() throws Exception {
        String ownerSubject = "auth0|chat-owner-push-down";
        String fixerSubject = "auth0|chat-fixer-push-down";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(fixerSubject, "GENERAL");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "GENERAL");

        ControllablePushGateway.DOWN.set(true);

        send(ownerSubject, requestId, messageBody("El notificador esta caido pero el mensaje debe llegar"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("El notificador esta caido pero el mensaje debe llegar"))
                .andExpect(jsonPath("$.notificationStatus").value("FAILED"));

        list(fixerSubject, requestId).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].notificationStatus").value("FAILED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }
}
