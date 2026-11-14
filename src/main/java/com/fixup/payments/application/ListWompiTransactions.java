package com.fixup.payments.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.payments.api.PaymentAccessDeniedException;
import com.fixup.payments.api.PaymentConflictException;
import com.fixup.payments.api.PaymentGatewayUnavailableException;
import com.fixup.payments.domain.HostedCheckoutGateway;
import com.fixup.payments.domain.HostedCheckoutGateway.ListedTransaction;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.WompiCheckouts;
import com.fixup.payments.domain.WompiCheckouts.CheckoutPayment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INT-01: transacciones del comercio en Wompi, consultadas con la llave privada, cruzadas con lo que
 * FixUp registró. Solo para la administración de la plataforma; los datos del comprador no salen de
 * Wompi. Sirve para ver los pagos de prueba (sandbox) sin entrar al panel de Wompi y para detectar
 * pagos aprobados que FixUp no registró o cobros dobles.
 */
@Service
public class ListWompiTransactions {
    static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> STATUSES = Set.of("APPROVED", "DECLINED", "VOIDED", "ERROR", "PENDING");

    private final HostedCheckoutGateway wompi;
    private final WompiCheckouts checkouts;
    private final Clock clock;

    ListWompiTransactions(HostedCheckoutGateway wompi, WompiCheckouts checkouts, Clock clock) {
        this.wompi = wompi;
        this.checkouts = checkouts;
        this.clock = clock;
    }

    /**
     * @param status APPROVED (por omisión), DECLINED, VOIDED, ERROR, PENDING o ALL
     */
    @Transactional(readOnly = true)
    public WompiTransactionList execute(CurrentActor actor, LocalDate from, LocalDate until, String status,
            int page, int pageSize) {
        if (actor.status() != UserStatus.ACTIVE || !actor.hasRole(Role.PLATFORM_ADMIN)) {
            throw new PaymentAccessDeniedException();
        }
        if (!wompi.enabled()) {
            throw new PaymentGatewayUnavailableException("WOMPI_NOT_CONFIGURED", "Wompi is not configured on this server");
        }
        LocalDate end = until != null ? until : LocalDate.now(clock.withZone(COLOMBIA));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new PaymentConflictException("INVALID_TRANSACTION_FILTER",
                    "The date range must go forward and cover at most " + MAX_DAYS + " days");
        }
        String filter = status == null || status.isBlank() ? "APPROVED" : status.trim().toUpperCase(Locale.ROOT);
        if (!filter.equals("ALL") && !STATUSES.contains(filter)) {
            throw new PaymentConflictException("INVALID_TRANSACTION_FILTER", "Unknown Wompi transaction status");
        }
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new PaymentConflictException("INVALID_TRANSACTION_FILTER",
                    "page starts at 1 and size goes from 1 to " + MAX_PAGE_SIZE);
        }

        var result = wompi.listTransactions(start, end, filter.equals("ALL") ? null : filter, page, pageSize);
        var links = checkouts.paymentsByReference(result.items().stream().map(ListedTransaction::reference).toList());
        var recorded = checkouts.recordedTransactions(result.items().stream().map(ListedTransaction::id).toList());
        var items = result.items().stream()
                .map(tx -> WompiTransactionView.of(tx, links.get(tx.reference()), recorded.get(tx.id())))
                .toList();
        return new WompiTransactionList(wompi.environment(), start, end, filter, result.page(), result.pageSize(),
                result.totalResults(), items);
    }

    /** Cómo se ve la transacción de Wompi desde FixUp. */
    @Schema(enumAsRef = true)
    public enum WompiReconciliation {
        /** FixUp registró el resultado de esta transacción. */
        RECORDED,
        /** Wompi la resolvió, pero FixUp no la registró (el pagador no volvió y no llegó el webhook). */
        NOT_RECORDED,
        /** Wompi todavía no la resuelve. */
        PENDING,
        /** Aprobada en Wompi para un pago que ya estaba aprobado por otra transacción: hay que reembolsarla. */
        DUPLICATE,
        /** La referencia es de FixUp, pero el monto o la moneda no coinciden con el checkout. */
        AMOUNT_MISMATCH,
        /** La referencia no salió de un checkout de FixUp (otra tienda o pruebas manuales en el comercio). */
        FOREIGN
    }

    public record WompiTransactionList(String environment, LocalDate from, LocalDate until, String status, int page,
            int pageSize, long totalResults, List<WompiTransactionView> items) {
    }

    public record WompiTransactionView(String transactionId, String reference, long amountInCents, String currency,
            String status, String paymentMethodType, String statusMessage, Instant createdAt, Instant finalizedAt,
            UUID paymentId, String paymentReference, String paymentPurpose, PaymentStatus paymentStatus,
            PaymentStatus recordedStatus, WompiReconciliation reconciliation) {

        static WompiTransactionView of(ListedTransaction tx, CheckoutPayment link, PaymentStatus recorded) {
            WompiReconciliation reconciliation;
            if (link == null) {
                reconciliation = WompiReconciliation.FOREIGN;
            } else if (tx.amountInCents() != link.checkoutAmountInCents() || !"COP".equals(tx.currency())) {
                reconciliation = WompiReconciliation.AMOUNT_MISMATCH;
            } else if (recorded != null) {
                reconciliation = WompiReconciliation.RECORDED;
            } else if ("PENDING".equals(tx.status())) {
                reconciliation = WompiReconciliation.PENDING;
            } else if ("APPROVED".equals(tx.status()) && link.status() == PaymentStatus.APPROVED) {
                reconciliation = WompiReconciliation.DUPLICATE;
            } else {
                reconciliation = WompiReconciliation.NOT_RECORDED;
            }
            return new WompiTransactionView(tx.id(), tx.reference(), tx.amountInCents(), tx.currency(), tx.status(),
                    tx.paymentMethodType(), tx.statusMessage(), tx.createdAt(), tx.finalizedAt(),
                    link == null ? null : link.paymentId(), link == null ? null : link.paymentReference(),
                    link == null ? null : link.purpose(), link == null ? null : link.status(), recorded,
                    reconciliation);
        }
    }
}
