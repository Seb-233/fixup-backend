package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.LedgerChecks;
import com.fixup.payments.domain.LedgerInvariant;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cada regla es una consulta que devuelve los identificadores que la rompen. Se escriben en SQL
 * plano, compatible con PostgreSQL y con el H2 de las pruebas, para poder correrlas también a mano
 * desde un cliente de base de datos durante la demostración.
 *
 * <p>Solo usan las columnas que el rol fixup_ledger_auditor puede leer (V47): si una regla nueva
 * necesita otra columna, hay que concederla en una migración o PostgreSQL rechaza la consulta.
 */
@Repository
class JdbcLedgerChecks implements LedgerChecks {
    static final String AUDITOR_ROLE = "fixup_ledger_auditor";
    /** Turno entre réplicas: "FIXUPSAF" en ASCII. Lo usa solo la conciliación. */
    static final long REPLICA_TURN_KEY = 0x4649585550534146L;

    /**
     * Corte digital: la fecha en que esta base aplicó V41. Desde ahí todo ingreso nace de un pago
     * digital aprobado y toda transferencia guarda su composición. Lo anterior no tiene contra qué
     * cruzarse; ver LedgerEquation.
     */
    private static final String DIGITAL_CUTOVER = installedOn("41");

    private static final String APPROVED_PAYMENT_OF = """
            EXISTS (SELECT 1 FROM repair_payments p
                    WHERE p.quotation_id = e.quotation_id AND p.fixer_user_id = e.fixer_user_id
                      AND p.status = 'APPROVED' AND p.purpose = '%s')""";
    private static final String NO_APPROVED_PAYMENT = """
            NOT EXISTS (SELECT 1 FROM repair_payments p
                        WHERE p.quotation_id = e.quotation_id AND p.fixer_user_id = e.fixer_user_id
                          AND p.status = 'APPROVED')""";
    private static final String IN_A_PAYOUT = "EXISTS (SELECT 1 FROM payout_entries pe WHERE pe.earning_id = e.id)";

    static final Map<LedgerInvariant, String> OFFENDERS = new EnumMap<>(LedgerInvariant.class);

    static {
        OFFENDERS.put(LedgerInvariant.SERVICE_PAYMENT_WITHOUT_EARNING, """
                SELECT p.id FROM repair_payments p
                WHERE p.status = 'APPROVED' AND p.purpose = 'SERVICE'
                  AND NOT EXISTS (SELECT 1 FROM fixer_earnings e
                      WHERE e.quotation_id = p.quotation_id AND e.fixer_user_id = p.fixer_user_id
                        AND e.gross_amount = p.amount)
                """);
        // Un ingreso anterior al corte digital puede no tener pago: no se le puede exigir aquí.
        OFFENDERS.put(LedgerInvariant.EARNING_WITHOUT_APPROVED_PAYMENT, """
                SELECT e.id FROM fixer_earnings e
                WHERE e.created_at >= %s
                  AND NOT EXISTS (SELECT 1 FROM repair_payments p
                    WHERE p.quotation_id = e.quotation_id AND p.fixer_user_id = e.fixer_user_id
                      AND p.status = 'APPROVED' AND p.amount = e.gross_amount)
                """.formatted(DIGITAL_CUTOVER));
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
                LEFT JOIN fixer_earnings e ON e.id = pe.earning_id
                WHERE e.id IS NULL OR e.status <> 'PAID_OUT' OR e.net_amount <> pe.net_amount
                UNION ALL
                SELECT e.id FROM fixer_earnings e
                WHERE e.status = 'PAID_OUT' AND e.paid_out_at >= %s AND NOT %s
                """.formatted(DIGITAL_CUTOVER, IN_A_PAYOUT));
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
                  AND (NOT EXISTS (SELECT 1 FROM owner_debts d
                           WHERE d.quotation_id = p.quotation_id AND d.status = 'PAID' AND d.amount = p.amount)
                       OR NOT EXISTS (SELECT 1 FROM fixer_earnings e
                           WHERE e.quotation_id = p.quotation_id AND e.fixer_user_id = p.fixer_user_id
                             AND e.gross_amount = p.amount))
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
        // Wompi (INT-01) registra cada transacción en esta misma tabla, así que la regla no cambia.
        OFFENDERS.put(LedgerInvariant.APPROVED_PAYMENT_WITHOUT_APPROVED_ATTEMPT, """
                SELECT p.id FROM repair_payments p
                WHERE p.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM simulated_payment_attempts a
                      WHERE a.payment_id = p.id AND a.resulting_status = 'APPROVED')
                """);
    }

    private static final String TOTALS = """
            SELECT
              (SELECT COALESCE(SUM(amount), 0) FROM repair_payments
                WHERE status = 'APPROVED' AND purpose = 'SERVICE') AS service_collected,
              (SELECT COALESCE(SUM(amount), 0) FROM repair_payments
                WHERE status = 'APPROVED' AND purpose = 'OUTSTANDING_BALANCE') AS debt_collected,
              (SELECT COALESCE(SUM(gross_amount), 0) FROM fixer_earnings) AS earnings_gross,
              (SELECT COALESCE(SUM(e.gross_amount), 0) FROM fixer_earnings e
                WHERE %1$s) AS service_earnings_gross,
              (SELECT COALESCE(SUM(e.gross_amount), 0) FROM fixer_earnings e
                WHERE %2$s) AS debt_earnings_gross,
              (SELECT COALESCE(SUM(e.gross_amount), 0) FROM fixer_earnings e
                WHERE e.created_at < %3$s AND %4$s) AS legacy_earnings_gross,
              (SELECT COALESCE(SUM(commission_amount), 0) FROM fixer_earnings) AS commission,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings) AS net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'HELD') AS held_net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'AVAILABLE') AS available_net,
              (SELECT COALESCE(SUM(net_amount), 0) FROM fixer_earnings WHERE status = 'PAID_OUT') AS paid_out_net,
              (SELECT COALESCE(SUM(amount), 0) FROM payouts WHERE operation_key IS NOT NULL) AS composed_payouts,
              (SELECT COALESCE(SUM(amount), 0) FROM payouts WHERE operation_key IS NULL) AS legacy_payouts,
              (SELECT COALESCE(SUM(net_amount), 0) FROM payout_entries) AS payout_entries_net,
              (SELECT COALESCE(SUM(e.net_amount), 0) FROM fixer_earnings e
                WHERE e.status = 'PAID_OUT' AND %5$s) AS composed_paid_out_net,
              (SELECT COALESCE(SUM(e.net_amount), 0) FROM fixer_earnings e
                WHERE e.status = 'PAID_OUT' AND NOT %5$s) AS legacy_paid_out_net,
              (SELECT COALESCE(SUM(amount), 0) FROM owner_debts WHERE status = 'PAID') AS paid_debts,
              (SELECT COALESCE(SUM(amount), 0) FROM owner_debts WHERE status = 'OUTSTANDING') AS outstanding_debts,
              (SELECT COALESCE(SUM(agreed_amount), 0) FROM cash_settlements) AS cash_agreed,
              (SELECT COALESCE(SUM(received_amount), 0) FROM cash_settlements) AS cash_received,
              (SELECT COALESCE(SUM(outstanding_amount), 0) FROM cash_settlements) AS cash_outstanding
            """.formatted(APPROVED_PAYMENT_OF.formatted("SERVICE"),
                    APPROVED_PAYMENT_OF.formatted("OUTSTANDING_BALANCE"), DIGITAL_CUTOVER, NO_APPROVED_PAYMENT,
                    IN_A_PAYOUT);

    private static final String SCANNED = """
            SELECT
              (SELECT COUNT(*) FROM repair_payments) AS payments,
              (SELECT COUNT(*) FROM simulated_payment_attempts) AS payment_attempts,
              (SELECT COUNT(*) FROM fixer_earnings) AS earnings,
              (SELECT COUNT(*) FROM payouts) AS payouts,
              (SELECT COUNT(*) FROM payout_entries) AS payout_entries,
              (SELECT COUNT(*) FROM owner_debts) AS debts,
              (SELECT COUNT(*) FROM cash_settlements) AS cash_settlements
            """;

    private final JdbcTemplate jdbc;
    private volatile Boolean postgres;

    JdbcLedgerChecks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String installedOn(String version) {
        return "(SELECT MIN(h.\"installed_on\") FROM \"flyway_schema_history\" h WHERE h.\"version\" = '"
                + version + "')";
    }

    @Override
    public boolean openSystemRead(Duration statementTimeout) {
        if (!postgres()) {
            // H2 (pruebas de contrato) no tiene RLS, roles ni réplicas.
            return true;
        }
        Boolean myTurn = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                REPLICA_TURN_KEY);
        if (!Boolean.TRUE.equals(myTurn)) {
            return false;
        }
        jdbc.execute("SET LOCAL ROLE " + AUDITOR_ROLE);
        jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                statementTimeout.toMillis() + "ms");
        String reader = jdbc.queryForObject("SELECT current_user", String.class);
        if (!AUDITOR_ROLE.equals(reader)) {
            throw new IllegalStateException("The ledger would be read as " + reader + ", not as " + AUDITOR_ROLE);
        }
        return true;
    }

    private boolean postgres() {
        Boolean known = postgres;
        if (known == null) {
            known = "PostgreSQL".equals(jdbc.execute(
                    (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()));
            postgres = known;
        }
        return known;
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
        return jdbc.queryForObject(TOTALS, (r, n) -> new Totals(r.getLong("service_collected"),
                r.getLong("debt_collected"), r.getLong("earnings_gross"), r.getLong("service_earnings_gross"),
                r.getLong("debt_earnings_gross"), r.getLong("legacy_earnings_gross"), r.getLong("commission"),
                r.getLong("net"), r.getLong("held_net"), r.getLong("available_net"), r.getLong("paid_out_net"),
                r.getLong("composed_payouts"), r.getLong("legacy_payouts"), r.getLong("payout_entries_net"),
                r.getLong("composed_paid_out_net"), r.getLong("legacy_paid_out_net"), r.getLong("paid_debts"),
                r.getLong("outstanding_debts"), r.getLong("cash_agreed"), r.getLong("cash_received"),
                r.getLong("cash_outstanding")));
    }

    @Override
    public Scanned scanned() {
        return jdbc.queryForObject(SCANNED, (r, n) -> new Scanned(r.getLong("payments"),
                r.getLong("payment_attempts"), r.getLong("earnings"), r.getLong("payouts"),
                r.getLong("payout_entries"), r.getLong("debts"), r.getLong("cash_settlements")));
    }
}
