-- INT-01: solo el pagador vigente (propietario o administrador inmobiliario delegado) abre un
-- checkout de Wompi, por el monto exacto del pago y mientras no esté aprobado. Las filas no se
-- modifican ni se borran. El webhook de Wompi corre sin actor, como los procesos internos.
ALTER TABLE wompi_checkouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE wompi_checkouts FORCE ROW LEVEL SECURITY;
CREATE POLICY wompi_checkouts_select ON wompi_checkouts FOR SELECT USING (
    EXISTS (SELECT 1 FROM repair_payments p WHERE p.id = payment_id)
);
CREATE POLICY wompi_checkouts_insert ON wompi_checkouts FOR INSERT WITH CHECK (
    created_by_user_id = app_current_user_id()
    AND EXISTS (SELECT 1 FROM repair_payments p LEFT JOIN repair_payment_contexts c ON c.quotation_id = p.quotation_id
        WHERE p.id = payment_id AND p.status <> 'APPROVED' AND p.payment_method = 'TRANSFER'
            AND wompi_checkouts.amount_in_cents = p.amount * 100
            AND financial_payer(c.property_id, p.owner_user_id))
);
REVOKE UPDATE, DELETE ON wompi_checkouts FROM fixup_app;
GRANT SELECT, INSERT ON wompi_checkouts TO fixup_app;
