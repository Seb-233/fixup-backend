package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * RNF-11 / ESC-S01: every operation published in docs/openapi.json answers 401 when the request
 * carries no credentials, so a new endpoint added without protection makes this test fail.
 * It checks authentication only; authorization per resource (403/404) is covered by the RLS and
 * contract tests.
 *
 * Intentional exception: the Wompi webhook is called by Wompi, not by a signed-in user, so it cannot
 * require a bearer token. It is listed in PUBLIC_BY_DESIGN and checked separately below.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationRequiredOnEveryEndpointTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    /** Operations that are public by design; each needs its own control below. */
    private static final Set<String> PUBLIC_BY_DESIGN = Set.of("POST /payments/wompi/events");

    @Autowired MockMvc mvc;

    private record Operation(String method, String path) {}

    private static List<Operation> contractOperations() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of("docs", "openapi.json")));
        List<Operation> operations = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(entry ->
                entry.getValue().fieldNames().forEachRemaining(method -> {
                    if (HTTP_METHODS.contains(method)) {
                        operations.add(new Operation(method.toUpperCase(), entry.getKey()));
                    }
                }));
        return operations;
    }

    @Test
    void everyOperationOfTheContractRejectsRequestsWithoutCredentials() throws Exception {
        var operations = contractOperations();
        assertThat(operations).as("operations read from docs/openapi.json").hasSizeGreaterThan(50);

        List<String> notRejected = new ArrayList<>();
        int checked = 0;
        for (var operation : operations) {
            if (PUBLIC_BY_DESIGN.contains(operation.method() + " " + operation.path())) {
                continue;
            }
            checked++;
            String uri = operation.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            int status = mvc.perform(request(HttpMethod.valueOf(operation.method()), uri))
                    .andReturn().getResponse().getStatus();
            if (status != 401) {
                notRejected.add(operation.method() + " " + operation.path() + " -> " + status);
            }
        }

        System.out.println("RNF-11 | operaciones del contrato: " + operations.size()
                + " | publicas por diseno (excepcion documentada): " + PUBLIC_BY_DESIGN.size()
                + " | recorridas sin token: " + checked
                + " | respondieron 401: " + (checked - notRejected.size()));
        notRejected.forEach(line -> System.out.println("RNF-11 |   NO RECHAZADA: " + line));

        assertThat(notRejected).as("operations that did not answer 401 without credentials").isEmpty();
    }

    @Test
    void publicByDesignOperationsExistInTheContractAndAreNotSilentlyGrowing() throws Exception {
        var contract = contractOperations().stream()
                .map(operation -> operation.method() + " " + operation.path())
                .toList();
        assertThat(contract).as("every exception must still exist in docs/openapi.json")
                .containsAll(PUBLIC_BY_DESIGN);
    }

    @Test
    void wompiWebhookIsPublicButRejectsAnUnsignedEvent() throws Exception {
        int status = mvc.perform(post("/payments/wompi/events")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
        System.out.println("RNF-11 | webhook de Wompi sin firma valida -> " + status);
        assertThat(status).as("an unsigned Wompi event must be rejected, not accepted")
                .isBetween(400, 499);
    }

    @Test
    void routesOutsideTheContractAreAlsoRejectedWithoutCredentials() throws Exception {
        for (String uri : List.of("/does-not-exist", "/admin", "/internal/anything", "/actuator/env")) {
            int status = mvc.perform(get(uri)).andReturn().getResponse().getStatus();
            assertThat(status).as("GET " + uri).isEqualTo(401);
        }
    }

    @Test
    void controlsShowThatPublicAndAuthenticatedPathsAreNotJustAlwaysRejected() throws Exception {
        int health = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(health).as("health is public").isNotIn(401, 403);

        int withIdentity = mvc.perform(get("/auth/me").with(jwt().jwt(token -> token.subject("auth0|rnf11-control"))))
                .andReturn().getResponse().getStatus();
        assertThat(withIdentity).as("a valid identity gets past authentication").isNotEqualTo(401);
    }
}
