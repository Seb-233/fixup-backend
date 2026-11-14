package com.fixup.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.payments.api.PaymentGatewayUnavailableException;
import com.fixup.payments.domain.HostedCheckoutGateway.CheckoutRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WompiPaymentGatewayTest {
    private static final String EVENTS_SECRET = "test_events_secret";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void integritySignatureMatchesTheOfficialWompiExample() {
        // Ejemplo de la documentación de Wompi (Widget & Checkout Web, firma de integridad).
        assertThat(WompiPaymentGateway.integritySignature("sk8-438k4-xmxm392-sn2m", 2490000, "COP",
                "prod_integrity_Z5mMke9x0k8gpErbDqwrJXMqsI6SFli6"))
                .isEqualTo("37c8407747e595535433ef8f6a811d853cd943046624a0ec04662b17bbf33bf5");
    }

    @Test
    void disabledUntilAllFourKeysExist() {
        var partial = new WompiPaymentGateway("pub_test_x", "prv_test_x", "", EVENTS_SECRET, "", "https://checkout.wompi.co/p/",
                Duration.ofSeconds(1), Duration.ofSeconds(1), new ObjectMapper());
        assertThat(partial.enabled()).isFalse();
        assertThatThrownBy(() -> partial.checkoutUrl(new CheckoutRequest("R", 100, "COP", null)))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(gateway("http://127.0.0.1:1").enabled()).isTrue();
        assertThat(gateway("http://127.0.0.1:1").environment()).isEqualTo("sandbox");
    }

    @Test
    void checkoutUrlCarriesTheSignedExactAmount() {
        String url = gateway("http://127.0.0.1:1").checkoutUrl(
                new CheckoutRequest("FXR-ABC-1234", 10_000_000, "COP", "http://localhost:4200/payments/p1"));
        assertThat(url).startsWith("https://checkout.wompi.co/p/?");
        Map<String, String> params = Arrays.stream(url.substring(url.indexOf('?') + 1).split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        assertThat(params).containsEntry("public-key", "pub_test_key")
                .containsEntry("currency", "COP")
                .containsEntry("amount-in-cents", "10000000")
                .containsEntry("reference", "FXR-ABC-1234")
                .containsEntry("redirect-url", "http://localhost:4200/payments/p1")
                .containsEntry("signature:integrity",
                        WompiPaymentGateway.integritySignature("FXR-ABC-1234", 10_000_000, "COP", "test_integrity_key"));
    }

    @Test
    void readsTheTransactionWithThePrivateKey() throws IOException {
        var authorization = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/transactions/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean known = exchange.getRequestURI().getPath().endsWith("/1234-1610641025-49201");
            byte[] body = known ? """
                    {"data":{"id":"1234-1610641025-49201","reference":"FXR-ABC-1234","amount_in_cents":10000000,
                    "currency":"COP","status":"APPROVED","payment_method_type":"CARD","status_message":null}}
                    """.getBytes(StandardCharsets.UTF_8) : "{\"error\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(known ? 200 : 404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var wompi = gateway("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");

        var transaction = wompi.findTransaction("1234-1610641025-49201").orElseThrow();
        assertThat(authorization.get()).isEqualTo("Bearer prv_test_key");
        assertThat(transaction.status()).isEqualTo("APPROVED");
        assertThat(transaction.amountInCents()).isEqualTo(10_000_000);
        assertThat(transaction.reference()).isEqualTo("FXR-ABC-1234");
        assertThat(transaction.paymentMethodType()).isEqualTo("CARD");
        assertThat(wompi.findTransaction("9999-0000000000-00000")).isEmpty();
        assertThat(wompi.findTransaction("../merchants")).isEmpty();
    }

    @Test
    void unreachableWompiIsReportedAsUnavailable() {
        assertThatThrownBy(() -> gateway("http://127.0.0.1:1/v1").findTransaction("1234-1-1"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }

    @Test
    void acceptsOnlyEventsSignedWithTheEventsSecret() {
        var wompi = gateway("http://127.0.0.1:1");
        String checksum = WompiPaymentGateway.sha256("1234-1610641025-49201APPROVED10000000" + "1530291411" + EVENTS_SECRET);
        String event = event(checksum, "test");

        assertThat(wompi.verifiedTransactionId(event, null)).contains("1234-1610641025-49201");
        assertThat(wompi.verifiedTransactionId(event, checksum.toUpperCase())).contains("1234-1610641025-49201");
        assertThat(wompi.verifiedTransactionId(event.replace("\"APPROVED\"", "\"DECLINED\""), null)).isEmpty();
        assertThat(wompi.verifiedTransactionId(event, "00" + checksum.substring(2))).isEmpty();
        assertThat(wompi.verifiedTransactionId(event(WompiPaymentGateway.sha256("forged"), "test"), null)).isEmpty();
        assertThat(wompi.verifiedTransactionId(event(checksum, "prod"), null)).isEmpty();
        assertThat(wompi.verifiedTransactionId("not json", null)).isEmpty();
    }

    static String event(String checksum, String environment) {
        return """
                {"event":"transaction.updated","data":{"transaction":{"id":"1234-1610641025-49201",
                "amount_in_cents":10000000,"reference":"FXR-ABC-1234","customer_email":"a@example.test",
                "currency":"COP","payment_method_type":"CARD","redirect_url":null,"status":"APPROVED",
                "shipping_address":null,"payment_link_id":null,"payment_source_id":null}},
                "environment":"%s","signature":{"properties":["transaction.id","transaction.status",
                "transaction.amount_in_cents"],"checksum":"%s"},"timestamp":1530291411,
                "sent_at":"2018-07-20T16:45:05.000Z"}
                """.formatted(environment, checksum);
    }

    private static WompiPaymentGateway gateway(String apiBaseUrl) {
        return new WompiPaymentGateway("pub_test_key", "prv_test_key", "test_integrity_key", EVENTS_SECRET,
                apiBaseUrl, "https://checkout.wompi.co/p/", Duration.ofSeconds(1), Duration.ofSeconds(2),
                new ObjectMapper());
    }
}
