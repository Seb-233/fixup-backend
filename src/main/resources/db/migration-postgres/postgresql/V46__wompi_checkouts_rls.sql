-- INT-01: aislamiento de los checkouts de Wompi para el rol de ejecución fixup_app.
--
-- Lectura: solo el pagador vigente del pago (propietario responsable o administrador inmobiliario
-- con la delegación vigente) y la administración de la plataforma (conciliación). El técnico, un
-- inquilino, otro propietario o un administrador inmobiliario ya revocado no ven la fila, aunque
-- el pago exista.
-- Alta: solo el pagador vigente, a su nombre, por el monto exacto, en pagos digitales todavía no
-- aprobados. Las filas no se modifican ni se borran.
-- El webhook de Wompi no corre sin actor: después de validar el checksum y de releer la transacción
-- en Wompi, aplica el resultado con la identidad de quien abrió el checkout, así que también pasa por
-- estas políticas (ver PayWithWompi).
ALTER TABLE wompi_checkouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE wompi_checkouts FORCE ROW LEVEL SECURITY;
CREATE POLICY wompi_checkouts_select ON wompi_checkouts FOR SELECT USING (
    financial_actor_role('PLATFORM_ADMIN')
    OR EXISTS (SELECT 1 FROM repair_payments p LEFT JOIN repair_payment_contexts c ON c.quotation_id = p.quotation_id
        WHERE p.id = wompi_checkouts.payment_id AND financial_payer(c.property_id, p.owner_user_id))
);
CREATE POLICY wompi_checkouts_insert ON wompi_checkouts FOR INSERT WITH CHECK (
    created_by_user_id = app_current_user_id()
    AND EXISTS (SELECT 1 FROM repair_payments p LEFT JOIN repair_payment_contexts c ON c.quotation_id = p.quotation_id
        WHERE p.id = wompi_checkouts.payment_id AND p.status <> 'APPROVED' AND p.payment_method = 'TRANSFER'
            AND wompi_checkouts.amount_in_cents = p.amount * 100
            AND financial_payer(c.property_id, p.owner_user_id))
);
REVOKE ALL ON wompi_checkouts FROM fixup_app;
GRANT SELECT, INSERT ON wompi_checkouts TO fixup_app;
