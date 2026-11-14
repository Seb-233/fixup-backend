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
 */
@Component
class WompiPaymentGateway implements HostedCheckoutGateway {
    private static final Logger log = LoggerFactory.getLogger(WompiPaymentGateway.class);
    private static final Pattern TRANSACTION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final String SANDBOX_API = "https://sandbox.wompi.co/v1";
    private static final String PRODUCTION_API = "https://production.wompi.co/v1";

    private final String publicKey;
    private final String privateKey;
    private final String integritySecret;
    private final String eventsSecret;
    private final String checkoutUrl;
    private final RestClient rest;
    private final ObjectMapper mapper;

    WompiPaymentGateway(@Value("${fixup.payments.wompi.public-key:}") String publicKey,
            @Value("${fixup.payments.wompi.private-key:}") String privateKey,
            @Value("${fixup.payments.wompi.integrity-secret:}") String integritySecret,
            @Value("${fixup.payments.wompi.events-secret:}") String eventsSecret,
            @Value("${fixup.payments.wompi.api-base-url:}") String apiBaseUrl,
            @Value("${fixup.payments.wompi.checkout-url:https://checkout.wompi.co/p/}") String checkoutUrl,
            @Value("${fixup.payments.wompi.connect-timeout:3s}") Duration connectTimeout,
            @Value("${fixup.payments.wompi.read-timeout:8s}") Duration readTimeout,
            ObjectMapper mapper) {
        this.publicKey = publicKey.trim();
        this.privateKey = privateKey.trim();
        this.integritySecret = integritySecret.trim();
        this.eventsSecret = eventsSecret.trim();
        this.checkoutUrl = checkoutUrl.trim();
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        String base = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? (this.publicKey.startsWith("pub_prod_") ? PRODUCTION_API : SANDBOX_API)
                : apiBaseUrl.trim();
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(base).build();
        if (enabled()) {
            log.info("Wompi habilitado en ambiente {} ({})", environment(), base);
        }
    }

    @Override
    public boolean enabled() {
        return !publicKey.isEmpty() && !privateKey.isEmpty() && !integritySecret.isEmpty()
                && !eventsSecret.isEmpty();
    }

    @Override
    public String environment() {
        return publicKey.startsWith("pub_prod_") ? "production" : "sandbox";
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
        return checkoutUrl + "?" + params.entrySet().stream()
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
        if (data == null || data.isMissingNode() || !data.hasNonNull("id")) {
            throw new PaymentGatewayUnavailableException("PAYMENT_GATEWAY_UNAVAILABLE",
                    "Wompi answered without a transaction");
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
        String expectedEnvironment = environment().equals("production") ? "prod" : "test";
        if (event.hasNonNull("environment") && !expectedEnvironment.equals(event.path("environment").asText())) {
            log.warn("Evento de Wompi descartado: ambiente {} distinto al configurado", event.path("environment").asText());
            return Optional.empty();
        }
        JsonNode properties = event.path("signature").path("properties");
        String checksum = event.path("signature").path("checksum").asText("");
        if (!properties.isArray() || properties.isEmpty() || checksum.isEmpty() || !event.hasNonNull("timestamp")) {
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

    private void requireEnabled() {
        if (!enabled()) {
            throw new PaymentGatewayUnavailableException("WOMPI_NOT_CONFIGURED",
                    "Wompi is not configured on this server");
        }
    }
}
