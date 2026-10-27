-- Prospective boundary. These flags describe rows already present at upgrade, not client input.
ALTER TABLE repair_requests ADD COLUMN legacy_cash BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jobs ADD COLUMN legacy_cash BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE repair_requests SET legacy_cash = TRUE WHERE payment_method = 'CASH';
UPDATE jobs SET legacy_cash = TRUE WHERE payment_method = 'CASH';

ALTER TABLE payouts ADD COLUMN operation_key UUID;
ALTER TABLE payouts ADD CONSTRAINT uq_payout_operation UNIQUE(fixer_user_id, operation_key);
ALTER TABLE payouts ADD CONSTRAINT uq_payout_owner UNIQUE(id, fixer_user_id);
ALTER TABLE fixer_earnings ADD CONSTRAINT uq_earning_owner UNIQUE(id, fixer_user_id);
CREATE TABLE payout_entries (
    earning_id UUID PRIMARY KEY,
    payout_id UUID NOT NULL,
    fixer_user_id UUID NOT NULL,
    net_amount BIGINT NOT NULL CHECK (net_amount >= 0),
    CONSTRAINT fk_payout_entry_payout FOREIGN KEY (payout_id, fixer_user_id) REFERENCES payouts(id, fixer_user_id),
    CONSTRAINT fk_payout_entry_earning FOREIGN KEY (earning_id, fixer_user_id) REFERENCES fixer_earnings(id, fixer_user_id)
);
CREATE INDEX ix_payout_entries_payout ON payout_entries(payout_id);
-- No backfill: a historical payout's composition cannot be inferred from overlapping dates/amounts.
