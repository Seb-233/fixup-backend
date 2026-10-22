ALTER TABLE jobs ADD COLUMN payment_method VARCHAR(16);
ALTER TABLE jobs ADD COLUMN agreed_amount BIGINT;

-- Jobs before FR-UC-05 used the platform transfer flow. The accepted quotation is
-- the authoritative historical price; no amount is inferred from an earning.
UPDATE jobs SET payment_method = 'TRANSFER';
UPDATE jobs SET agreed_amount = (
    SELECT q.amount FROM quotations q WHERE q.id = jobs.quotation_id
);
ALTER TABLE jobs ALTER COLUMN payment_method SET NOT NULL;
ALTER TABLE jobs ALTER COLUMN agreed_amount SET NOT NULL;
ALTER TABLE jobs ADD CONSTRAINT ck_job_payment_method CHECK (payment_method IN ('TRANSFER', 'CASH'));
ALTER TABLE jobs ADD CONSTRAINT ck_job_agreed_amount CHECK (agreed_amount > 0);

CREATE TABLE cash_settlements (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    quotation_id UUID NOT NULL REFERENCES quotations(id),
    request_id UUID NOT NULL REFERENCES repair_requests(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    fixer_user_id UUID NOT NULL REFERENCES users(id),
    agreed_amount BIGINT NOT NULL,
    received_amount BIGINT NOT NULL,
    outstanding_amount BIGINT NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_cash_settlement_job UNIQUE (job_id),
    CONSTRAINT ck_cash_settlement_amounts CHECK (
        agreed_amount > 0 AND received_amount >= 0
        AND received_amount <= agreed_amount
        AND outstanding_amount = agreed_amount - received_amount
    )
);
CREATE INDEX ix_cash_settlements_owner ON cash_settlements (owner_user_id, settled_at DESC);
CREATE INDEX ix_cash_settlements_fixer ON cash_settlements (fixer_user_id, settled_at DESC);

ALTER TABLE payouts ADD COLUMN transferred_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE payouts DROP CONSTRAINT ck_payout_status;
ALTER TABLE payouts ADD CONSTRAINT ck_payout_status CHECK (status IN ('REQUESTED', 'TRANSFERRED'));
ALTER TABLE payouts ADD CONSTRAINT ck_payout_transfer CHECK (
    (status = 'REQUESTED' AND transferred_at IS NULL)
    OR (status = 'TRANSFERRED' AND transferred_at IS NOT NULL)
);
