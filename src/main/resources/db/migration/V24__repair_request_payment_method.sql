-- FR-UC-05: payment method is agreed when a repair request is opened.
-- Existing requests predate this choice and are normalized to TRANSFER before the column becomes
-- mandatory. There is intentionally no DEFAULT: every new request must state its method.
ALTER TABLE repair_requests ADD COLUMN payment_method VARCHAR(16);

UPDATE repair_requests
SET payment_method = 'TRANSFER'
WHERE payment_method IS NULL;

ALTER TABLE repair_requests
    ADD CONSTRAINT ck_repair_request_payment_method
    CHECK (payment_method IN ('TRANSFER', 'CASH'));

ALTER TABLE repair_requests
    ALTER COLUMN payment_method SET NOT NULL;
