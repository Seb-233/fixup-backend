-- INT-01: pasarela Wompi (Web Checkout). Cada vez que el pagador abre el checkout se crea una
-- referencia nueva, porque Wompi no deja reutilizar una referencia ya usada. La fila guarda qué
-- pago cubre, el monto exacto en centavos y quién la abrió; el resultado de cada transacción de
-- Wompi queda, como los intentos simulados, en simulated_payment_attempts (instrumento WOMPI).
CREATE TABLE wompi_checkouts (
    reference VARCHAR(64) PRIMARY KEY,
    payment_id UUID NOT NULL,
    amount_in_cents BIGINT NOT NULL CHECK (amount_in_cents > 0),
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_wompi_checkout_payment FOREIGN KEY (payment_id) REFERENCES repair_payments(id),
    CONSTRAINT fk_wompi_checkout_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id)
);
CREATE INDEX ix_wompi_checkouts_payment ON wompi_checkouts(payment_id);

ALTER TABLE simulated_payment_attempts DROP CONSTRAINT ck_attempt_enrichment;
ALTER TABLE simulated_payment_attempts ADD CONSTRAINT ck_attempt_enrichment CHECK (
    (operation_key IS NULL AND payment_reference IS NULL AND amount IS NULL AND instrument IS NULL
        AND scenario IS NULL AND gateway_transaction_id IS NULL AND failure_reason IS NULL)
    OR (operation_key IS NOT NULL AND payment_reference IS NOT NULL AND amount > 0 AND instrument IS NOT NULL
        AND instrument IN ('SANDBOX_CARD', 'SANDBOX_PSE', 'SANDBOX_NEQUI', 'WOMPI') AND scenario IS NOT NULL
        AND gateway_transaction_id IS NOT NULL)
);
