package com.fixup.payments.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * INT-01: puerto hacia una pasarela con checkout alojado (Wompi). El pagador escribe los datos de
 * su medio de pago en la página de la pasarela, nunca en FixUp. El resultado no se toma del
 * navegador: se consulta a la pasarela por el identificador de la transacción.
 */
public interface HostedCheckoutGateway {

    /** Falso mientras falte alguna llave: la pasarela simulada sigue disponible. */
    boolean enabled();

    /** {@code sandbox} o {@code production}, según las llaves configuradas. */
    String environment();

    /** URL firmada del checkout alojado para una referencia y un monto exactos. */
    String checkoutUrl(CheckoutRequest request);

    /** Estado autoritativo de una transacción, consultado a la pasarela con la llave privada. */
    Optional<ProviderTransaction> findTransaction(String transactionId);

    /**
     * Transacciones del comercio creadas entre dos fechas (inclusive), más recientes primero, para la
     * vista de administración. {@code status} nulo trae todas.
     */
    TransactionPage listTransactions(LocalDate from, LocalDate until, String status, int page, int pageSize);

    /**
     * Valida la firma de un evento enviado por la pasarela y devuelve el identificador de la
     * transacción que reporta. Vacío si el evento no es auténtico o no es de una transacción.
     */
    Optional<String> verifiedTransactionId(String rawEvent, String checksumHeader);

    record CheckoutRequest(String reference, long amountInCents, String currency, String redirectUrl) {
    }

    record ListedTransaction(String id, String reference, long amountInCents, String currency, String status,
            String paymentMethodType, String statusMessage, Instant createdAt, Instant finalizedAt) {
    }

    record TransactionPage(List<ListedTransaction> items, int page, int pageSize, long totalResults) {
    }

    record ProviderTransaction(String id, String reference, long amountInCents, String currency,
            String status, String paymentMethodType, String statusMessage) {
    }
}
