package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerInvariant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cada regla es una consulta que devuelve los identificadores que la rompen. Se escriben en SQL
 * plano, compatible con PostgreSQL y con el H2 de las pruebas, para poder correrlas también a mano
 * desde un cliente de base de datos durante la demostración.
 */
@Repository
class JdbcLedgerChecks implements LedgerChecks {
    static final Map<LedgerInvariant, String> OFFENDERS = new EnumMap<>(LedgerInvariant.class);

    static {
        OFFENDERS.put(LedgerInvariant.SERVICE_PAYMENT_WITHOUT_EARNING, """
                SELECT p.id FROM repair_payments p
                WHERE p.status = 'APPROVED' AND p.purpose = 'SERVICE'
                  AND NOT EXISTS (SELECT 1 FROM fixer_earnings e
                      WHERE e.quotation_id = p.quotation_id AND e.fixer_user_id = p.fixer_user_id
                        AND e.gross_amount = p.amount)
                """);
        OFFENDERS.put(LedgerInvariant.EARNING_WITHOUT_APPROVED_PAYMENT, """
                SELECT e.id FROM fixer_earnings e
                WHERE NOT EXISTS (SELECT 1 FROM repair_payments p
                    WHERE p.quotation_id = e.quotation_id AND p.fixer_user_id = e.fixer_user_id
                      AND p.status = 'APPROVED' AND p.amount = e.gross_amount)
                """);
        OFFENDERS.put(LedgerInvariant.EARNING_COMMISSION_MISMATCH, """
                SELECT e.id FROM fixer_earnings e
                WHERE e.commission_amount <> (e.gross_amount * e.commission_rate_bps) / 10000
                   OR e.commission_amount + e.net_amount <> e.gross_amount
                """);
        // Las transferencias anteriores a V41 no guardan su composición: solo se revisan las que sí.
        OFFENDERS.put(LedgerInvariant.PAYOUT_TOTAL_MISMATCH, """
                SELECT po.id FROM payouts po
                WHERE po.operation_key IS NOT NULL
                  AND (po.amount <> COALESCE((SELECT SUM(pe.net_amount) FROM payout_entries pe
                                              WHERE pe.payout_id = po.id), 0)
                       OR po.earning_count <> (SELECT COUNT(*) FROM payout_entries pe
                                               WHERE pe.payout_id = po.id))
                """);
        OFFENDERS.put(LedgerInvariant.PAYOUT_ENTRY_MISMATCH, """
                SELECT pe.earning_id AS id FROM payout_entries pe
                JOIN fixer_earnings e ON e.id = pe.earning_id
                WHERE e.status <> 'PAID_OUT' OR e.net_amount <> pe.net_amount
                """);
        OFFENDERS.put(LedgerInvariant.PAID_DEBT_WITHOUT_PAYMENT, """
                SELECT d.id FROM owner_debts d
                WHERE d.status = 'PAID'
                  AND NOT EXISTS (SELECT 1 FROM repair_payments p
                      WHERE p.quotation_id = d.quotation_id AND p.purpose = 'OUTSTANDING_BALANCE'
                        AND p.status = 'APPROVED' AND p.amount = d.amount)
                """);
        OFFENDERS.put(LedgerInvariant.DEBT_PAYMENT_NOT_APPLIED, """
                SELECT p.id FROM repair_payments p
                WHERE p.status = 'APPROVED' AND p.purpose = 'OUTSTANDING_BALANCE'
                  AND NOT EXISTS (SELECT 1 FROM owner_debts d
                      WHERE d.quotation_id = p.quotation_id AND d.status = 'PAID' AND d.amount = p.amount)
                """);
        OFFENDERS.put(LedgerInvariant.CASH_SETTLEMENT_DEBT_MISMATCH, """
                SELECT s.id FROM cash_settlements s
                WHERE (s.outstanding_amount > 0
                       AND NOT EXISTS (SELECT 1 FROM owner_debts d
                           WHERE d.quotation_id = s.quotation_id AND d.amount = s.outstanding_amount))
                   OR (s.outstanding_amount = 0
                       AND EXISTS (SELECT 1 FROM owner_debts d WHERE d.quotation_id = s.quotation_id))
                UNION ALL
                SELECT d.id FROM owner_debts d
                WHERE NOT EXISTS (SELECT 1 FROM cash_settlements s WHERE s.quotation_id = d.quotation_id)
                """);
        OFFENDERS.put(LedgerInvariant.APPROVED_PAYMENT_WITHOUT_APPROVED_ATTEMPT, """
                SELECT p.id FROM repair_payments p
                WHERE p.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM simulated_payment_attempts a
                      WHERE a.payment_id = p.id AND a.resulting_status = 'APPROVED')
                """);
    }

    private static final String TOTALS = """
            SELECT
              (SELECT COALESCE(SUM(amount), 0) FROM repair_payments WHERE status = 'APPROVED') AS collected,
              (SELECT COALESCE(SUM(gross_amount), 0) FROM fixer_earnings) AS earnings_gross,
              (SELECT COALESCE(SUM(commission_amount), 0) FROM fixer_earnings) AS commission,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings) AS net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'HELD') AS held_net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'AVAILABLE') AS available_net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'PAID_OUT') AS paid_out_net,
              (SELECT COALESCE(SUM(amount), 0) FROM payouts) AS payouts,
              (SELECT COALESCE(SUM(amount), 0) FROM owner_debts WHERE status = 'OUTSTANDING') AS outstanding_debts
            """;

    private final JdbcTemplate jdbc;

    JdbcLedgerChecks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Finding check(LedgerInvariant invariant, int sampleLimit) {
        String offenders = OFFENDERS.get(invariant);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + offenders + ") x", Long.class);
        var sample = jdbc.queryForList("SELECT x.id FROM (" + offenders + ") x ORDER BY x.id LIMIT ?",
                UUID.class, sampleLimit);
        return new Finding(count == null ? 0 : count, sample);
    }

    @Override
    public Totals totals() {
        return jdbc.queryForObject(TOTALS, (r, n) -> new Totals(r.getLong("collected"),
                r.getLong("earnings_gross"), r.getLong("commission"), r.getLong("net"),
                r.getLong("held_net"), r.getLong("available_net"), r.getLong("paid_out_net"),
                r.getLong("payouts"), r.getLong("outstanding_debts")));
    }
}
