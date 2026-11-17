package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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
 * <p>The only exceptions are the operations in {@link #PUBLIC_OPERATIONS}: callers that cannot send a
 * bearer token and authenticate the request another way. Each one must be declared public in the
 * contract ({@code security: []}) and keep its own rejection test here, so the list cannot grow or
 * go stale silently.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Wompi en sandbox para que el webhook valide de verdad el checksum. La API apunta a un puerto
// cerrado del loopback: ninguna prueba de esta clase debe llegar a llamarla con éxito.
@TestPropertySource(properties = {
        "fixup.payments.wompi.public-key=pub_test_rnf11",
        "fixup.payments.wompi.private-key=prv_test_rnf11",
        "fixup.payments.wompi.integrity-secret=test_integrity_rnf11",
        "fixup.payments.wompi.events-secret=" + AuthenticationRequiredOnEveryEndpointTest.EVENTS_SECRET,
        "fixup.payments.wompi.api-base-url=http://127.0.0.1:9/v1",
        "fixup.payments.wompi.connect-timeout=500ms",
        "fixup.payments.wompi.read-timeout=500ms"
})
class AuthenticationRequiredOnEveryEndpointTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
    static final String EVENTS_SECRET = "test_events_rnf11";

    /**
     * Operations of the contract that are public by design, with the reason. Adding one here needs
     * a matching {@code security: []} in docs/openapi.json and a test below that shows how it
     * rejects unauthenticated callers.
     */
    private static final Map<String, String> PUBLIC_OPERATIONS = Map.of(
            "POST /payments/wompi/events",
            "INT-01: Wompi calls the webhook without a bearer token; each event is authenticated "
                    + "with the SHA-256 checksum signed with the events secret "
                    + "(covered by webhookRejectsEventsWithoutAValidChecksumEvenThoughItNeedsNoToken).");

    @Autowired MockMvc mvc;

    private record Operation(String method, String path) {
        String key() {
            return method + " " + path;
        }
    }

    /** Operations that the contract declares public with an empty security requirement. */
    private static Set<String> contractPublicOperations() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of("docs", "openapi.json")));
        Set<String> declared = new TreeSet<>();
        root.path("paths").fields().forEachRemaining(entry ->
                entry.getValue().fields().forEachRemaining(method -> {
                    JsonNode security = method.getValue().path("security");
                    if (HTTP_METHODS.contains(method.getKey()) && security.isArray() && security.isEmpty()) {
                        declared.add(method.getKey().toUpperCase() + " " + entry.getKey());
                    }
                }));
        return declared;
    }

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
        int skipped = 0;
        for (var operation : operations) {
            if (PUBLIC_OPERATIONS.containsKey(operation.key())) {
                skipped++;
                continue;
            }
            String uri = operation.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
            int status = mvc.perform(request(HttpMethod.valueOf(operation.method()), uri))
                    .andReturn().getResponse().getStatus();
            if (status != 401) {
                notRejected.add(operation.method() + " " + operation.path() + " -> " + status);
            }
        }

        int checked = operations.size() - skipped;
        System.out.println("RNF-11 | operaciones del contrato recorridas sin token: " + checked
                + " | respondieron 401: " + (checked - notRejected.size())
                + " | públicas por diseño (excluidas): " + skipped);
        notRejected.forEach(line -> System.out.println("RNF-11 |   NO RECHAZADA: " + line));

        assertThat(notRejected).as("operations that did not answer 401 without credentials").isEmpty();
        assertThat(skipped).as("every public exception is an operation of the contract")
                .isEqualTo(PUBLIC_OPERATIONS.size());
    }

    @Test
    void thePublicExceptionsAreExactlyTheOperationsTheContractDeclaresPublic() throws Exception {
        assertThat(contractPublicOperations())
                .as("operations with security: [] in docs/openapi.json")
                .containsExactlyInAnyOrderElementsOf(PUBLIC_OPERATIONS.keySet());
    }

    @Test
    void webhookRejectsEventsWithoutAValidChecksumEvenThoughItNeedsNoToken() throws Exception {
        String id = "12345-1-RNF11";
        String validChecksum = sha256(id + "APPROVED" + 10_000_000 + 1_700_000_000L + EVENTS_SECRET);

        // Sin firma, firmado con otro secreto, o con el contenido alterado después de firmar.
        assertThat(webhook(id, 10_000_000, "", null)).as("no checksum").isEqualTo(401);
        String forged = sha256(id + "APPROVED" + 10_000_000 + 1_700_000_000L + "test_events_forged");
        assertThat(webhook(id, 10_000_000, forged, forged)).as("checksum signed with another secret").isEqualTo(401);
        assertThat(webhook(id, 100, validChecksum, validChecksum)).as("amount changed after signing").isEqualTo(401);
        assertThat(webhook(id, 10_000_000, validChecksum, "00" + validChecksum.substring(2)))
                .as("header checksum that does not match the body").isEqualTo(401);

        // Control: con el checksum correcto pasa la verificación y llega a consultar Wompi, que aquí
        // no responde (503). El 401 anterior viene de la firma, no de un rechazo incondicional.
        assertThat(webhook(id, 10_000_000, validChecksum, validChecksum)).as("valid checksum").isEqualTo(503);
    }

    private int webhook(String id, long cents, String bodyChecksum, String headerChecksum) throws Exception {
        String body = """
                {"event":"transaction.updated","data":{"transaction":{"id":"%s","amount_in_cents":%d,
                "reference":"rnf11","currency":"COP","payment_method_type":"CARD","status":"APPROVED"}},
                "environment":"test","signature":{"properties":["transaction.id","transaction.status",
                "transaction.amount_in_cents"],"checksum":"%s"},"timestamp":1700000000,
                "sent_at":"2026-11-16T12:00:00.000Z"}
                """.formatted(id, cents, bodyChecksum);
        var request = post("/payments/wompi/events").contentType(MediaType.APPLICATION_JSON).content(body);
        if (headerChecksum != null) {
            request = request.header("X-Event-Checksum", headerChecksum);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
