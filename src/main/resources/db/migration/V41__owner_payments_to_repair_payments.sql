-- FR-UC-05: el pago de reparaciones queda en un solo modelo, repair_payments (transferencia o
-- efectivo). Los cobros al propietario de owner_payments (V34) pasan a pagos por transferencia del
-- servicio, con su mismo estado, y la tabla anterior se retira.
INSERT INTO repair_payments (id, quotation_id, request_id, owner_user_id, fixer_user_id, manager_user_id, amount,
        payment_method, purpose, status, created_at, updated_at, completed_at)
SELECT op.id, op.quotation_id, op.request_id, op.owner_user_id, op.fixer_user_id, op.manager_user_id, op.amount,
       'TRANSFER', 'SERVICE', op.status, op.created_at, op.updated_at,
       CASE WHEN op.status = 'APPROVED' THEN op.approved_at ELSE NULL END
FROM owner_payments op
WHERE NOT EXISTS (
    SELECT 1 FROM repair_payments rp WHERE rp.quotation_id = op.quotation_id AND rp.purpose = 'SERVICE'
);

DROP TABLE owner_payments;
