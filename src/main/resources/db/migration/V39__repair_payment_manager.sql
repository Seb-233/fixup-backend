-- FR-UC-11 y FR-UC-05: el administrador inmobiliario del inmueble delegado también puede pagar la
-- reparación, así que el pago guarda quién lo administra.
ALTER TABLE repair_payments ADD COLUMN manager_user_id UUID;
CREATE INDEX ix_repair_payments_manager ON repair_payments (manager_user_id, created_at DESC);
