package com.fixup.integration;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, ChatHttpContract.ControllablePushGatewayConfiguration.class})
@Testcontainers
class PostgresChatIT extends ChatHttpContract {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * FR-UC-24: overridden for the same reason as the FR-UC-25/23 equivalents. A stranger has no
     * relationship at all to the assigned request, so RLS on repair_requests hides it before
     * SendChatMessage/ListChatMessages ever gets a snapshot to check ChatAccess.requireParticipant
     * against -- it is NOT_FOUND, same as if the request never existed, rather than the H2 profile's
     * ACCESS_DENIED (which never confirms the resource's existence to an unrelated caller either).
     */
    @Test
    @Override
    void aStrangerCannotReadOrSendMessages() throws Exception {
        String ownerSubject = "auth0|chat-owner-priv";
        String fixerSubject = "auth0|chat-fixer-priv";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(fixerSubject, "ELECTRICAL");
        UUID requestId = createAssignedRequest(ownerSubject, fixerSubject, "ELECTRICAL");
        send(ownerSubject, requestId, messageBody("Mensaje privado")).andExpect(status().isCreated());

        provision("auth0|chat-stranger");
        list("auth0|chat-stranger", requestId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        send("auth0|chat-stranger", requestId, messageBody("Puedo ver esto?"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
    }

    /**
     * FR-UC-24: overridden for the same reason. Once the request is ASSIGNED, RLS only admits its
     * owner and its assigned fixer (the OPEN-marketplace branch no longer applies), so a different,
     * unrelated fixer gets NOT_FOUND instead of the H2 profile's ACCESS_DENIED.
     */
    @Test
    @Override
    void anUnrelatedFixerCannotReadOrSendMessages() throws Exception {
        String ownerSubject = "auth0|chat-owner-other-fixer";
        String assignedFixerSubject = "auth0|chat-fixer-assigned";
        String otherFixerSubject = "auth0|chat-fixer-other";
        provisionOwner(ownerSubject);
        provisionVerifiedFixer(assignedFixerSubject, "PAINTING");
        provisionVerifiedFixer(otherFixerSubject, "PAINTING");
        UUID requestId = createAssignedRequest(ownerSubject, assignedFixerSubject, "PAINTING");

        list(otherFixerSubject, requestId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
        send(otherFixerSubject, requestId, messageBody("Yo tambien cotice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }
}
