package com.fixup.payments.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.payments.api.PaymentGatewayUnavailableException;
import com.fixup.payments.domain.HostedCheckoutGateway;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * INT-01: adaptador de Wompi (Colombia). Usa tres mecanismos de la pasarela:
 * <ul>
 *   <li><b>Web Checkout</b> por redirección (HTTPS GET) con la firma de integridad
 *       SHA-256(referencia + monto en centavos + moneda + secreto de integridad), para que nadie
 *       cambie el monto en el navegador.</li>
 *   <li><b>API REST</b> {@code GET /v1/transactions/{id}} con la llave privada como Bearer: es la
 *       fuente de verdad del resultado; la redirección del navegador solo trae el id.</li>
 *   <li><b>Eventos</b> (webhook) {@code transaction.updated}, con checksum SHA-256 de las propiedades
 *       que el evento declara + timestamp + secreto de eventos.</li>
 * </ul>
 * Los tiempos de conexión y lectura son cortos (táctica timeout): si Wompi no responde, el pago
 * queda como estaba y el pagador puede volver a verificar.
 *
 * <p><b>Solo sandbox (fail-closed).</b> El alcance académico autoriza únicamente el ambiente de
 * pruebas, también en la instancia pública. El adaptador no conoce la URL de producción: se habilita
 * solo si las cuatro llaves son de prueba ({@code pub_test_}, {@code prv_test_},
 * {@code test_integrity_}, {@code test_events_}) y la API es la del sandbox. Una llave de producción,
 * una mezcla de ambientes o una URL de API externa distinta del sandbox lo dejan apagado (no
 * arranca a medias), y entonces ninguna operación sale hacia Wompi. Una URL de API local
 * (loopback) se acepta solo para las pruebas automáticas con un Wompi falso.
 */
@Component
class WompiPaymentGateway implements HostedCheckoutGateway {
    private static final Logger log = LoggerFactory.getLogger(WompiPaymentGateway.class);
    private static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    static final String SANDBOX_API = "https://sandbox.wompi.co/v1";
    /** Wompi atiende el checkout de los dos ambientes en el mismo host; el ambiente sale de la llave. */
    static final String CHECKOUT_URL = "https://checkout.wompi.co/p/";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String publicKey;
    private final String privateKey;
    private final String integritySecret;
    private final String eventsSecret;
    private final boolean enabled;
    private final RestClient rest;
    private final ObjectMapper mapper;

    WompiPaymentGateway(@Value("${fixup.payments.wompi.public-key:}") String publicKey,
            @Value("${fixup.payments.wompi.private-key:}") String privateKey,
            @Value("${fixup.payments.wompi.integrity-secret:}") String integritySecret,
            @Value("${fixup.payments.wompi.events-secret:}") String eventsSecret,
            @Value("${fixup.payments.wompi.api-base-url:}") String apiBaseUrl,
            @Value("${fixup.payments.wompi.connect-timeout:3s}") Duration connectTimeout,
            @Value("${fixup.payments.wompi.read-timeout:8s}") Duration readTimeout,
            ObjectMapper mapper) {
        this.publicKey = publicKey == null ? "" : publicKey.trim();
        this.privateKey = privateKey == null ? "" : privateKey.trim();
        this.integritySecret = integritySecret == null ? "" : integritySecret.trim();
        this.eventsSecret = eventsSecret == null ? "" : eventsSecret.trim();
        this.mapper = mapper;
        String base = apiBaseUrl == null || apiBaseUrl.isBlank() ? SANDBOX_API : apiBaseUrl.trim();
        this.enabled = acceptedConfiguration(base);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        // Sin configuración aceptada el cliente apunta a una dirección que no existe: aunque algún
        // camino se saltara requireEnabled(), no habría a dónde enviar una transacción.
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(enabled ? base : "http://wompi.disabled.invalid")
                .build();
        if (enabled) {
            log.info("Wompi habilitado en ambiente sandbox ({})", base);
        }
    }

    private boolean acceptedConfiguration(String base) {
        boolean any = !publicKey.isEmpty() || !privateKey.isEmpty() || !integritySecret.isEmpty() || !eventsSecret.isEmpty();
        if (!any) {
            return false;
        }
        var problems = new java.util.ArrayList<String>();
        if (!publicKey.startsWith("pub_test_")) problems.add("la llave pública no es pub_test_");
        if (!privateKey.startsWith("prv_test_")) problems.add("la llave privada no es prv_test_");
        if (!integritySecret.startsWith("test_integrity_")) problems.add("el secreto de integridad no es test_integrity_");
        if (!eventsSecret.startsWith("test_events_")) problems.add("el secreto de eventos no es test_events_");
        if (!base.equals(SANDBOX_API) && !isLoopback(base)) problems.add("la URL de la API no es la del sandbox");
        if (!problems.isEmpty()) {
            // Nunca se registran los valores: solo qué regla falló.
            log.error("Wompi queda deshabilitado: solo se admite el sandbox ({})", String.join("; ", problems));
            return false;
        }
        return true;
    }

    private static boolean isLoopback(String base) {
        try {
            var uri = java.net.URI.create(base);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && LOOPBACK.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String environment() {
        return "sandbox";
    }

    @Override
    public String checkoutUrl(CheckoutRequest request) {
        requireEnabled();
        var params = new LinkedHashMap<String, String>();
        params.put("public-key", publicKey);
        params.put("currency", request.currency());
        params.put("amount-in-cents", Long.toString(request.amountInCents()));
        params.put("reference", request.reference());
        params.put("signature:integrity", integritySignature(request.reference(), request.amountInCents(),
                request.currency(), integritySecret));
        if (request.redirectUrl() != null) {
            params.put("redirect-url", request.redirectUrl());
        }
        // Igual que el formulario GET de la documentación de Wompi: nombres y valores codificados.
        return CHECKOUT_URL + "?" + params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    @Override
    public Optional<ProviderTransaction> findTransaction(String transactionId) {
        requireEnabled();
        if (transactionId == null || !TRANSACTION_ID.matcher(transactionId).matches()) {
            return Optional.empty();
        }
        final JsonNode body;
        try {
            body = rest.get().uri("/transactions/{id}", transactionId)
                    .header("Authorization", "Bearer " + privateKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden rejected) {
            throw new PaymentGatewayUnavailableException("WOMPI_CREDENTIALS_REJECTED",
                    "Wompi rejected the configured private key", rejected);
        } catch (RestClientException failure) {
            // El cuerpo de la falla no se propaga: podría traer datos del comercio.
            throw new PaymentGatewayUnavailableException("PAYMENT_GATEWAY_UNAVAILABLE",
                    "Wompi did not answer; the payment was not changed", failure);
        }
        JsonNode data = body == null ? null : body.path("data");
        if (data == null || !transactionId.equals(text(data, "id"))
                || !nonBlankText(data, "reference") || !nonBlankText(data, "currency")
                || !nonBlankText(data, "status")
                || !data.path("amount_in_cents").isIntegralNumber()
                || !data.path("amount_in_cents").canConvertToLong()
                || data.path("amount_in_cents").longValue() <= 0) {
            throw new PaymentGatewayUnavailableException("PAYMENT_GATEWAY_UNAVAILABLE",
                    "Wompi answered without a complete matching transaction; retry verification");
        }
        return Optional.of(new ProviderTransaction(data.path("id").asText(), data.path("reference").asText(),
                data.path("amount_in_cents").asLong(-1), data.path("currency").asText(),
                data.path("status").asText(), text(data, "payment_method_type"), text(data, "status_message")));
    }

    @Override
    public TransactionPage listTransactions(LocalDate from, LocalDate until, String status, int page, int pageSize) {
        requireEnabled();
        final JsonNode body;
        try {
            body = rest.get().uri(uri -> {
                        uri.path("/transactions")
                                .queryParam("from_date", from)
                                .queryParam("until_date", until)
                                .queryParam("page", page)
                                .queryParam("page_size", pageSize);
                        if (status != null) {
                            uri.queryParam("status", status);
                        }
                        return uri.build();
                    })
                    .header("Authorization", "Bearer " + privateKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden rejected) {
            throw new PaymentGatewayUnavailableException("WOMPI_CREDENTIALS_REJECTED",
                    "Wompi rejected the configured private key", rejected);
        } catch (RestClientException failure) {
            throw new PaymentGatewayUnavailableException("PAYMENT_GATEWAY_UNAVAILABLE",
                    "Wompi did not answer the transaction list", failure);
        }
        JsonNode data = body == null ? null : body.path("data");
        if (data == null || !data.isArray()) {
            throw new PaymentGatewayUnavailableException("PAYMENT_GATEWAY_UNAVAILABLE",
                    "Wompi answered without a transaction list");
        }
        var items = new ArrayList<ListedTransaction>();
        for (JsonNode tx : data) {
            // Solo se copian los campos de conciliación: los datos del comprador se quedan en Wompi.
            items.add(new ListedTransaction(tx.path("id").asText(), tx.path("reference").asText(),
                    tx.path("amount_in_cents").asLong(-1), tx.path("currency").asText(), tx.path("status").asText(),
                    text(tx, "payment_method_type"), text(tx, "status_message"),
                    instant(tx, "created_at"), instant(tx, "finalized_at")));
        }
        items.sort(Comparator.comparing(ListedTransaction::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        JsonNode meta = body.path("meta");
        return new TransactionPage(items, meta.path("page").asInt(page), meta.path("page_size").asInt(pageSize),
                meta.path("total_results").asLong(items.size()));
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException unexpected) {
            return null;
        }
    }

    @Override
    public Optional<String> verifiedTransactionId(String rawEvent, String checksumHeader) {
        if (!enabled() || rawEvent == null || rawEvent.isBlank()) {
            return Optional.empty();
        }
        final JsonNode event;
        try {
            event = mapper.readTree(rawEvent);
        } catch (Exception malformed) {
            return Optional.empty();
        }
        if (!"transaction.updated".equals(event.path("event").asText())) {
            return Optional.empty();
        }
        if (!"test".equals(event.path("environment").asText(""))) {
            log.warn("Evento de Wompi descartado: ambiente {} distinto al configurado", event.path("environment").asText());
            return Optional.empty();
        }
        JsonNode properties = event.path("signature").path("properties");
        String checksum = event.path("signature").path("checksum").asText("");
        if (!properties.isArray() || properties.isEmpty() || checksum.isEmpty() || !event.hasNonNull("timestamp")) {
            return Optional.empty();
        }
        // El id que se usa debe estar firmado: sin esto, un evento auténtico podría reusarse con otro id.
        boolean idSigned = false;
        for (JsonNode property : properties) {
            idSigned |= "transaction.id".equals(property.asText());
        }
        if (!idSigned) {
            log.warn("Evento de Wompi descartado: la firma no cubre transaction.id");
            return Optional.empty();
        }
        var concatenated = new StringBuilder();
        for (JsonNode property : properties) {
            JsonNode value = event.path("data");
            for (String segment : property.asText().split("\\.")) {
                value = value.path(segment);
            }
            if (value.isMissingNode() || value.isContainerNode()) {
                return Optional.empty();
            }
            concatenated.append(value.isNull() ? "" : value.asText());
        }
        concatenated.append(event.path("timestamp").asText()).append(eventsSecret);
        String computed = sha256(concatenated.toString());
        if (!constantTimeEqualsIgnoreCase(computed, checksum)
                || (checksumHeader != null && !checksumHeader.isBlank()
                    && !constantTimeEqualsIgnoreCase(computed, checksumHeader.trim()))) {
            log.warn("Evento de Wompi descartado: checksum inválido");
            return Optional.empty();
        }
        String transactionId = event.path("data").path("transaction").path("id").asText("");
        return TRANSACTION_ID.matcher(transactionId).matches() ? Optional.of(transactionId) : Optional.empty();
    }

    /** SHA-256 en hexadecimal de referencia + centavos + moneda + secreto, como lo pide Wompi. */
    static String integritySignature(String reference, long amountInCents, String currency, String secret) {
        return sha256(reference + amountInCents + currency + secret);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean constantTimeEqualsIgnoreCase(String a, String b) {
        return MessageDigest.isEqual(a.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                b.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static boolean nonBlankText(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).textValue().isBlank();
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new PaymentGatewayUnavailableException("WOMPI_NOT_CONFIGURED",
                    "Wompi is not configured on this server");
        }
    }
}
