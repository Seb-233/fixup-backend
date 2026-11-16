package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentGatewayUnavailableException;
import com.fixup.payments.api.PaymentNotFoundException;
import com.fixup.payments.domain.HostedCheckoutGateway;
import com.fixup.payments.domain.HostedCheckoutGateway.ProviderTransaction;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.RepairPayment;
import com.fixup.payments.domain.RepairPaymentMethod;
import com.fixup.payments.domain.RepairPayments;
import com.fixup.payments.domain.WompiCheckout;
import com.fixup.payments.domain.WompiCheckouts;
import com.fixup.shared.security.DatabaseActorContext;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INT-01: pago de la reparación o de una deuda con Wompi (Web Checkout).
 *
 * <ol>
 *   <li>{@link #start}: el pagador vigente pide un checkout. FixUp crea una referencia nueva
 *       ({@code FXR-…-XXXXXXXX}), la guarda con el monto exacto en centavos y devuelve la URL firmada.</li>
 *   <li>El pagador paga en la página de Wompi; FixUp nunca ve datos de tarjeta, PSE o Nequi.</li>
 *   <li>{@link #confirm} (al volver el navegador) y {@link #onEvent} (webhook firmado) consultan la
 *       transacción a Wompi con la llave privada y aplican el resultado con un actor y su RLS. Los dos
 *       caminos convergen en {@link WompiSettlement#settle} y son idempotentes: la llave de operación
 *       sale del id de la transacción.</li>
 * </ol>
 * Un resultado PENDING no se registra: se vuelve a consultar hasta que Wompi lo resuelva.
 */
@Service
public class PayWithWompi {
    private static final Logger log = LoggerFactory.getLogger(PayWithWompi.class);
    private static final String CURRENCY = "COP";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final String WEBHOOK_SUBJECT = "wompi:webhook";

    private final HostedCheckoutGateway wompi;
    private final WompiCheckouts checkouts;
    private final RepairPayments payments;
    private final PaymentAuthorization authorization;
    private final WompiSettlement settlement;
    private final UserDirectory users;
    private final Clock clock;
    private final Set<String> returnOrigins;

    PayWithWompi(HostedCheckoutGateway wompi, WompiCheckouts checkouts, RepairPayments payments,
            PaymentAuthorization authorization, WompiSettlement settlement, UserDirectory users, Clock clock, @Value("${fixup.cors.allowed-origins:}") String corsOrigins,
            @Value("${fixup.payments.wompi.return-origins:}") String returnOrigins) {
        this.wompi = wompi;
        this.checkouts = checkouts;
        this.payments = payments;
        this.authorization = authorization;
        this.settlement = settlement;
        this.users = users;
        this.clock = clock;
        this.returnOrigins = Stream.concat(Arrays.stream(corsOrigins.split(",")), Arrays.stream(returnOrigins.split(",")))
                .map(String::trim).filter(origin -> !origin.isEmpty())
                .map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
                .map(origin -> origin.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public WompiStatus status() {
        return new WompiStatus(wompi.enabled(), wompi.enabled() ? wompi.environment() : null);
    }

    @Transactional
    public WompiCheckoutSession start(CurrentActor actor, UUID paymentId, String returnUrl) {
        requireEnabled();
        var payment = payments.findByIdForUpdate(paymentId).orElseThrow(PaymentNotFoundException::new);
        authorization.requirePayer(actor, payment);
        requireDigital(payment);
        if (payment.status() == PaymentStatus.APPROVED) {
            throw new PaymentConflictException("PAYMENT_ALREADY_APPROVED", "An approved payment cannot be paid again");
        }
        String redirectUrl = validatedReturnUrl(returnUrl, paymentId);
        long amountInCents = Math.multiplyExact(payment.amount(), 100L);
        String reference = payment.reference() + "-"
                + HexFormat.of().withUpperCase().formatHex(randomBytes(4));
        checkouts.create(new WompiCheckout(reference, payment.id(), amountInCents, actor.internalUserId(),
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        String url = wompi.checkoutUrl(new HostedCheckoutGateway.CheckoutRequest(reference, amountInCents, CURRENCY,
                redirectUrl));
        return new WompiCheckoutSession(payment.id(), reference, amountInCents, CURRENCY, wompi.environment(), url);
    }

    /**
     * Llamado al volver del checkout con el id que Wompi agrega a la URL de retorno. La consulta a
     * Wompi va fuera de la transacción de base de datos (no retiene una conexión mientras Wompi
     * responde); el resultado se aplica después, con el RLS del pagador.
     */
    public WompiTransactionResult confirm(CurrentActor actor, UUID paymentId, String transactionId) {
        requireEnabled();
        var transaction = wompi.findTransaction(transactionId)
                .orElseThrow(() -> new PaymentConflictException("WOMPI_TRANSACTION_NOT_FOUND",
                        "Wompi does not know this transaction"));
        return settlement.settle(transaction, paymentId, actor);
    }

    /**
     * Webhook {@code transaction.updated}. Solo se usa el id del evento firmado: el estado, el monto y
     * la referencia se vuelven a pedir a Wompi. El resultado no se aplica como proceso interno sin
     * aislamiento: se aplica con la identidad de quien abrió el checkout (sus roles vigentes, leídos de
     * la base), con las mismas reglas y el mismo RLS que cuando él vuelve del checkout. Si ya no es el
     * pagador vigente, no se registra nada y la transacción queda "sin registrar" en la conciliación.
     *
     * @return falso si el evento no es auténtico
     * @throws PaymentGatewayUnavailableException si Wompi no responde: el 503 hace que Wompi reintente
     */
    public boolean onEvent(String rawEvent, String checksumHeader) {
        var transactionId = wompi.verifiedTransactionId(rawEvent, checksumHeader);
        if (transactionId.isEmpty()) {
            return false;
        }
        var transaction = wompi.findTransaction(transactionId.get());
        if (transaction.isEmpty()) {
            log.warn("Evento de Wompi para la transacción {} que la API no reconoce", transactionId.get());
            return true;
        }
        var opener = settlement.openerOf(transaction.get().reference());
        if (opener.isEmpty()) {
            log.warn("Evento de Wompi no aplicado: la referencia de la transacción {} no es de FixUp",
                    transaction.get().id());
            return true;
        }
        Set<Role> roles = Stream.of(Role.OWNER, Role.REAL_ESTATE_MANAGER)
                .filter(role -> users.hasActiveRole(opener.get(), role))
                .collect(Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) {
            log.warn("Evento de Wompi no aplicado: quien abrió el checkout de la transacción {} ya no puede pagar",
                    transaction.get().id());
            return true;
        }
        var actor = new CurrentActor(opener.get(), WEBHOOK_SUBJECT, roles, UserStatus.ACTIVE);
        var previous = DatabaseActorContext.current();
        DatabaseActorContext.set(new DatabaseActorContext.Actor(opener.get(),
                roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet())));
        try {
            var result = settlement.settle(transaction.get(), null, actor);
            log.info("Evento de Wompi aplicado: transacción {} {} -> pago {}", result.transactionId(),
                    result.transactionStatus(), result.paymentStatus());
        } catch (PaymentConflictException | PaymentAccessDeniedException notApplied) {
            // No se reintenta: Wompi solo reenvía si no hay 200, y el caso queda en la conciliación.
            log.warn("Evento de Wompi no aplicado para la transacción {}: {}", transaction.get().id(),
                    notApplied.getMessage());
        } finally {
            previous.ifPresentOrElse(DatabaseActorContext::set, DatabaseActorContext::clear);
        }
        return true;
    }

    private String validatedReturnUrl(String returnUrl, UUID paymentId) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return null;
        }
        final URI uri;
        try {
            uri = URI.create(returnUrl.trim());
        } catch (IllegalArgumentException invalid) {
            throw new PaymentConflictException("INVALID_RETURN_URL", "The return URL is not valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new PaymentConflictException("INVALID_RETURN_URL", "The return URL is not valid");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (rejectedByCheckout(host)) {
            throw new PaymentConflictException("WOMPI_RETURN_URL_LOCAL",
                    "Wompi's checkout rejects return URLs on localhost or an IP address; open FixUp at "
                            + "http://fixup.localhost:<port> or at its public HTTPS address");
        }
        String origin = (scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        if (!returnOrigins.contains(origin)) {
            throw new PaymentConflictException("RETURN_ORIGIN_NOT_ALLOWED",
                    "The return origin is not configured (CORS_ALLOWED_ORIGINS or WOMPI_RETURN_ORIGINS)");
        }
        if (!uri.getRawPath().endsWith("/payments/" + paymentId)) {
            throw new PaymentConflictException("INVALID_RETURN_URL", "The return URL must open this payment");
        }
        return uri.toString();
    }

    /**
     * checkout.wompi.co (CloudFront) answers 403 when redirect-url points at localhost or an IP literal,
     * so the payer would never reach the checkout. Subdomains of localhost (fixup.localhost) are accepted
     * and browsers resolve them to this machine.
     */
    static boolean rejectedByCheckout(String host) {
        return host.equals("localhost") || host.startsWith("[") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private void requireEnabled() {
        if (!wompi.enabled()) {
            throw new PaymentGatewayUnavailableException("WOMPI_NOT_CONFIGURED", "Wompi is not configured on this server");
        }
    }

    private static void requireDigital(RepairPayment payment) {
        if (payment.paymentMethod() != RepairPaymentMethod.TRANSFER) {
            throw new PaymentConflictException("PAYMENT_METHOD_NOT_SUPPORTED", "Only digital payments go through Wompi");
        }
    }

    private static byte[] randomBytes(int size) {
        var bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public record WompiStatus(boolean enabled, String environment) { }

    public record WompiCheckoutSession(UUID paymentId, String reference, long amountInCents, String currency,
            String environment, String checkoutUrl) { }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record WompiTransactionResult(String transactionId, String transactionStatus, PaymentStatus paymentStatus,
            String paymentMethodType, String statusMessage, PaymentAttemptResult attempt) {
        static WompiTransactionResult of(ProviderTransaction transaction, PaymentStatus paymentStatus,
                PaymentAttemptResult attempt) {
            return new WompiTransactionResult(transaction.id(), transaction.status(), paymentStatus,
                    transaction.paymentMethodType(), transaction.statusMessage(), attempt);
        }
    }
}
