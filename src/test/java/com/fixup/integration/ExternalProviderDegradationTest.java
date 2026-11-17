package com.fixup.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixup.testsupport.IntegrationDatabaseCleaner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * RNF-16: el adaptador externo habilitado (geocodificación) intercambia datos de forma controlada y
 * maneja indisponibilidad, fallos y tiempos de espera.
 *
 * <p>A diferencia de las pruebas unitarias del adaptador, que sustituyen al cliente HTTP, aquí se
 * levanta un proveedor en un socket real y se apunta el adaptador de FixUp a él con la misma
 * configuración que se usa en despliegue. Así se ejerce el camino real completo: petición HTTP
 * saliente, tiempo de espera configurado en la fábrica de peticiones, mapeo del payload y respuesta
 * pública con degradación controlada.
 *
 * <p>El proveedor simulado es el extremo remoto, no el adaptador: nadie parchea la clase de
 * producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfiguration.class)
class ExternalProviderDegradationTest {

    private static final String SUBJECT = "auth0|external-provider-reader";
    private static final Duration PROVIDER_READ_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration PROVIDER_DELAY_BEYOND_TIMEOUT = Duration.ofSeconds(3);

    /** Debe existir antes de que arranque el contexto: el adaptador fija su URL base al crearse. */
    static final StubProvider PROVIDER = StubProvider.start();

    @DynamicPropertySource
    static void pointTheAdapterAtTheStubProvider(DynamicPropertyRegistry registry) {
        registry.add("fixup.geocoding.enabled", () -> true);
        registry.add("fixup.geocoding.base-url", PROVIDER::baseUrl);
        registry.add("fixup.geocoding.connect-timeout", () -> "1s");
        registry.add("fixup.geocoding.read-timeout", () -> PROVIDER_READ_TIMEOUT.toSeconds() + "s");
        registry.add("fixup.geocoding.cache-ttl", () -> "10m");
        registry.add("fixup.geocoding.max-cache-entries", () -> 8);
    }

    @Autowired MockMvc mvc;
    @Autowired IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void isolate() throws Exception {
        databaseCleaner.clean();
        PROVIDER.reset();
        // El adaptador limita a una petición externa por segundo: se espera a que abra la ventana
        // para que cada prueba mida lo suyo y no un 429 heredado de la anterior.
        Thread.sleep(1_100);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Test
    void aConfiguredExternalProviderIsCalledOverRealHttpAndItsPayloadIsMapped() throws Exception {
        bootstrap();
        PROVIDER.respond(200, """
                [{
                  "place_id": 271687046,
                  "osm_type": "way",
                  "display_name": "Carrera 7, Bogotá, Colombia",
                  "lat": "4.657000",
                  "lon": "-74.055000"
                }]
                """);

        geocode("Carrera 7").andExpect(status().isOk())
                .andExpect(header().string("X-Data-Attribution", "\u00A9 OpenStreetMap contributors"))
                .andExpect(jsonPath("$[0].displayName").value("Carrera 7, Bogotá, Colombia"))
                .andExpect(jsonPath("$[0].latitude").value(4.657))
                .andExpect(jsonPath("$[0].longitude").value(-74.055));

        // La forma exacta de la petición saliente es la que exige el proveedor configurado.
        assertThat(PROVIDER.requests()).singleElement().satisfies(recorded -> {
            assertThat(recorded.path()).isEqualTo("/search");
            assertThat(recorded.decodedQuery())
                    .contains("q=Carrera 7")
                    .contains("format=jsonv2")
                    .contains("limit=5")
                    .contains("countrycodes=co")
                    .contains("addressdetails=1");
            assertThat(recorded.userAgent()).startsWith("FixUp/");
        });
    }

    @Test
    void aProviderReadTimeoutDegradesToAControlled503InBoundedTime() throws Exception {
        bootstrap();
        PROVIDER.respondAfter(PROVIDER_DELAY_BEYOND_TIMEOUT, 200, "[]");

        long startedAt = System.nanoTime();
        var response = geocode("Carrera 100").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Address geocoding is temporarily unavailable"))
                .andReturn();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        // El tiempo de espera configurado corta la llamada: no se espera al proveedor ni se agota
        // el hilo que atiende la petición.
        assertThat(elapsedMillis).isLessThan(2_500L);
        assertNoProviderDetailsLeak(response.getResponse().getContentAsString());
    }

    @Test
    void aProviderServerErrorDegradesToAControlled503() throws Exception {
        bootstrap();
        PROVIDER.respond(500, "{\"error\":\"internal\"}");

        var response = geocode("Carrera 200").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Address geocoding is temporarily unavailable"))
                .andReturn();

        assertNoProviderDetailsLeak(response.getResponse().getContentAsString());
    }

    @Test
    void anUnusableProviderPayloadDegradesToAControlled503AndNeverToAClientError() throws Exception {
        bootstrap();
        PROVIDER.respond(200, "esto no es json");

        var response = geocode("Carrera 300").andExpect(status().isServiceUnavailable())
                .andReturn();

        assertNoProviderDetailsLeak(response.getResponse().getContentAsString());
    }

    @Test
    void providerThrottlingIsReportedAsAControlled429() throws Exception {
        bootstrap();
        PROVIDER.respond(429, "{\"error\":\"too many requests\"}");

        var response = geocode("Carrera 400").andExpect(status().isTooManyRequests())
                .andReturn();

        assertNoProviderDetailsLeak(response.getResponse().getContentAsString());
    }

    private ResultActions geocode(String query) throws Exception {
        return mvc.perform(get("/locations/geocode").param("query", query).with(identity()));
    }

    private void bootstrap() throws Exception {
        mvc.perform(post("/auth/bootstrap").with(identity())).andExpect(status().isCreated());
        mvc.perform(post("/auth/select-role").with(identity())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TENANT\"}"))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor identity() {
        return jwt().jwt(token -> token.subject(SUBJECT).claim("email", "reader@example.test")
                .claim("name", "Synthetic reader"));
    }

    /** Lo que el cliente de FixUp recibe no puede delatar al proveedor ni su error interno. */
    private static void assertNoProviderDetailsLeak(String responseBody) {
        assertThat(responseBody)
                .doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(PROVIDER.port()))
                .doesNotContain("SocketTimeout")
                .doesNotContain("RestClient")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    /** Extremo remoto simulado, servido en un socket real y local. */
    static final class StubProvider {
        private final HttpServer server;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private volatile int status = 200;
        private volatile String body = "[]";
        private volatile Duration delay = Duration.ZERO;

        private StubProvider(HttpServer server) {
            this.server = server;
        }

        static StubProvider start() {
            try {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                var provider = new StubProvider(server);
                server.createContext("/", provider::handle);
                // Un hilo por petición: un proveedor lento no debe encolar a los demás. Son hilos
                // demonio para que una respuesta lenta abandonada no retenga la construcción.
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    var thread = new Thread(runnable, "stub-provider");
                    thread.setDaemon(true);
                    return thread;
                }));
                server.start();
                return provider;
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot start the stub provider", failure);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        int port() {
            return server.getAddress().getPort();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void respond(int status, String body) {
            respondAfter(Duration.ZERO, status, body);
        }

        void respondAfter(Duration delay, int status, String body) {
            this.status = status;
            this.body = body;
            this.delay = delay;
        }

        void reset() {
            requests.clear();
            respond(200, "[]");
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new RecordedRequest(exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst(HttpHeaders.USER_AGENT)));
            var activeDelay = delay;
            if (!activeDelay.isZero()) {
                try {
                    Thread.sleep(activeDelay.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            } finally {
                exchange.close();
            }
        }

        record RecordedRequest(String path, String rawQuery, String userAgent) {
            String decodedQuery() {
                return rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
            }
        }
    }
}
