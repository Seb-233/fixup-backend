package com.fixup.integration;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Un libro de pagos representativo, escrito directo en las tablas del libro.
 *
 * <p>Hoy la aplicación ya no deja crear trabajos en efectivo ni transferencias sin composición, así
 * que esos casos históricos no se pueden producir por HTTP: se insertan como quedaron en la base.
 * Para no arrastrar toda la cadena de solicitudes, cotizaciones y trabajos, las escrituras apagan
 * llaves foráneas y triggers (session_replication_role = replica en PostgreSQL, REFERENTIAL_INTEGRITY
 * en H2). Las CHECK de cada tabla siguen vigentes. Es también la forma en que un script con
 * privilegios dañaría el libro, que es lo que la conciliación tiene que atrapar.
 *
 * <p>Qué contiene (tres propietarios, dos técnicos):
 * <ul>
 *   <li>servicio pagado al segundo intento, ingreso retenido;</li>
 *   <li>servicio pagado con un intento enriquecido (llave de operación, referencia, transacción del
 *       proveedor: la forma que también usa Wompi), ingreso disponible;</li>
 *   <li>servicio pagado e ingreso transferido en una transferencia con composición;</li>
 *   <li>trabajo histórico en efectivo con abono, deuda pagada después por la plataforma e ingreso de
 *       saldo transferido en la misma transferencia;</li>
 *   <li>otro servicio del mismo técnico todavía disponible (transferencia parcial);</li>
 *   <li>trabajo histórico en efectivo pagado completo, sin deuda;</li>
 *   <li>trabajo histórico en efectivo con deuda pendiente y un pago de saldo rechazado, por reintentar;</li>
 *   <li>pago de servicio rechazado, sin ingreso;</li>
 *   <li>ingreso y transferencia anteriores al libro digital, sin pago ni composición.</li>
 * </ul>
 */
final class LedgerFixture {
    static final long SERVICE_COLLECTED = 450_000L + 1_200_000L + 800_000L + 600_000L;
    static final long DEBT_COLLECTED = 200_000L;
    static final long LEGACY_GROSS = 100_000L;
    static final long EARNINGS_GROSS = SERVICE_COLLECTED + DEBT_COLLECTED + LEGACY_GROSS;
    static final long COMMISSION = (SERVICE_COLLECTED + DEBT_COLLECTED) / 10 + 17_500L;
    static final long HELD_NET = 405_000L;
    static final long AVAILABLE_NET = 1_080_000L + 540_000L;
    static final long COMPOSED_PAYOUT = 720_000L + 180_000L;
    static final long LEGACY_PAYOUT = 82_500L;

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    private static final OffsetDateTime BEFORE_THE_LEDGER = OffsetDateTime.of(2020, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    final UUID owner1 = UUID.randomUUID();
    final UUID owner2 = UUID.randomUUID();
    final UUID owner3 = UUID.randomUUID();
    final UUID fixer1 = UUID.randomUUID();
    final UUID fixer2 = UUID.randomUUID();

    final Case heldService = new Case();
    final Case availableService = new Case();
    final Case paidOutService = new Case();
    final Case paidDebt = new Case();
    final Case secondAvailableService = new Case();
    final Case cashInFull = new Case();
    final Case pendingDebt = new Case();
    final Case declinedService = new Case();
    final Case beforeTheLedger = new Case();
    final UUID composedPayout = UUID.randomUUID();
    final UUID legacyPayout = UUID.randomUUID();

    /** Los identificadores de una cotización y de las filas del libro que cuelgan de ella. */
    static final class Case {
        final UUID quotation = UUID.randomUUID();
        final UUID request = UUID.randomUUID();
        final UUID payment = UUID.randomUUID();
        final UUID earning = UUID.randomUUID();
        final UUID debt = UUID.randomUUID();
        final UUID settlement = UUID.randomUUID();
        final UUID approvedAttempt = UUID.randomUUID();
    }

    private final JdbcTemplate jdbc;
    private final boolean postgres;

    LedgerFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.postgres = "PostgreSQL".equals(jdbc.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()));
    }

    boolean postgres() {
        return postgres;
    }

    /** Escribe con llaves foráneas y triggers apagados, en una sola conexión y una sola transacción. */
    void write(Consumer<JdbcTemplate> work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var session = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            boolean autoCommit = connection.getAutoCommit();
            session.execute(postgres ? "SET session_replication_role = replica" : "SET REFERENTIAL_INTEGRITY FALSE");
            connection.setAutoCommit(false);
            try {
                work.accept(session);
                connection.commit();
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
                session.execute(postgres ? "SET session_replication_role = origin" : "SET REFERENTIAL_INTEGRITY TRUE");
            }
            return null;
        });
    }

    void tamper(String sql, Object... args) {
        write(session -> session.update(sql, args));
    }

    LedgerFixture seed() {
        write(db -> {
            payment(db, heldService, owner1, fixer1, 450_000, "SERVICE", "APPROVED");
            attempt(db, heldService.payment, UUID.randomUUID(), "DECLINED", owner1);
            attempt(db, heldService.payment, heldService.approvedAttempt, "APPROVED", owner1);
            earning(db, heldService, fixer1, 450_000, "HELD", NOW);

            payment(db, availableService, owner2, fixer1, 1_200_000, "SERVICE", "APPROVED");
            enrichedAttempt(db, availableService, 1_200_000, owner2);
            earning(db, availableService, fixer1, 1_200_000, "AVAILABLE", NOW);

            payment(db, paidOutService, owner1, fixer2, 800_000, "SERVICE", "APPROVED");
            attempt(db, paidOutService.payment, paidOutService.approvedAttempt, "APPROVED", owner1);
            earning(db, paidOutService, fixer2, 800_000, "PAID_OUT", NOW);

            cashSettlement(db, paidDebt, owner3, fixer2, 300_000, 100_000);
            debt(db, paidDebt, owner3, fixer2, 200_000, "PAID");
            payment(db, paidDebt, owner3, fixer2, 200_000, "OUTSTANDING_BALANCE", "APPROVED");
            attempt(db, paidDebt.payment, paidDebt.approvedAttempt, "APPROVED", owner3);
            earning(db, paidDebt, fixer2, 200_000, "PAID_OUT", NOW);

            payout(db, composedPayout, fixer2, COMPOSED_PAYOUT, 2, UUID.randomUUID());
            payoutEntry(db, paidOutService.earning, composedPayout, fixer2, 720_000);
            payoutEntry(db, paidDebt.earning, composedPayout, fixer2, 180_000);

            payment(db, secondAvailableService, owner2, fixer2, 600_000, "SERVICE", "APPROVED");
            attempt(db, secondAvailableService.payment, secondAvailableService.approvedAttempt, "APPROVED", owner2);
            earning(db, secondAvailableService, fixer2, 600_000, "AVAILABLE", NOW);

            cashSettlement(db, cashInFull, owner1, fixer1, 250_000, 250_000);

            cashSettlement(db, pendingDebt, owner2, fixer1, 500_000, 350_000);
            debt(db, pendingDebt, owner2, fixer1, 150_000, "OUTSTANDING");
            payment(db, pendingDebt, owner2, fixer1, 150_000, "OUTSTANDING_BALANCE", "PENDING");
            attempt(db, pendingDebt.payment, UUID.randomUUID(), "DECLINED", owner2);

            payment(db, declinedService, owner3, fixer1, 90_000, "SERVICE", "DECLINED");
            attempt(db, declinedService.payment, UUID.randomUUID(), "DECLINED", owner3);

            db.update("""
                    INSERT INTO fixer_earnings (id, quotation_id, fixer_user_id, gross_amount, commission_amount,
                        net_amount, commission_rate_bps, status, created_at, released_at, paid_out_at, updated_at)
                    VALUES (?, ?, ?, 100000, 17500, 82500, 1750, 'PAID_OUT', ?, ?, ?, ?)
                    """, beforeTheLedger.earning, beforeTheLedger.quotation, fixer1, BEFORE_THE_LEDGER,
                    BEFORE_THE_LEDGER, BEFORE_THE_LEDGER, BEFORE_THE_LEDGER);
            db.update("""
                    INSERT INTO payouts (id, fixer_user_id, amount, earning_count, status, requested_at)
                    VALUES (?, ?, ?, 1, 'REQUESTED', ?)
                    """, legacyPayout, fixer1, LEGACY_PAYOUT, BEFORE_THE_LEDGER);
        });
        return this;
    }

    void payment(JdbcTemplate db, Case c, UUID owner, UUID fixer, long amount, String purpose, String status) {
        db.update("""
                INSERT INTO repair_payments (id, quotation_id, request_id, owner_user_id, fixer_user_id, amount,
                    payment_method, purpose, status, created_at, updated_at, completed_at, reference)
                VALUES (?, ?, ?, ?, ?, ?, 'TRANSFER', ?, ?, ?, ?, ?, ?)
                """, c.payment, c.quotation, c.request, owner, fixer, amount, purpose, status, NOW, NOW,
                status.equals("APPROVED") ? NOW : null, reference(c.payment));
    }

    void attempt(JdbcTemplate db, UUID payment, UUID id, String outcome, UUID payer) {
        db.update("""
                INSERT INTO simulated_payment_attempts (id, payment_id, requested_outcome, resulting_status,
                    attempted_by_user_id, attempted_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, payment, outcome, outcome, payer, NOW);
    }

    private void enrichedAttempt(JdbcTemplate db, Case c, long amount, UUID payer) {
        db.update("""
                INSERT INTO simulated_payment_attempts (id, payment_id, requested_outcome, resulting_status,
                    attempted_by_user_id, attempted_at, operation_key, payment_reference, amount, instrument,
                    scenario, gateway_transaction_id)
                VALUES (?, ?, 'APPROVED', 'APPROVED', ?, ?, ?, ?, ?, 'SANDBOX_PSE', 'APPROVE', ?)
                """, c.approvedAttempt, c.payment, payer, NOW, UUID.randomUUID(), reference(c.payment), amount,
                "TX-" + c.approvedAttempt);
    }

    void earning(JdbcTemplate db, Case c, UUID fixer, long gross, String status, OffsetDateTime createdAt) {
        long commission = gross / 10;
        db.update("""
                INSERT INTO fixer_earnings (id, quotation_id, fixer_user_id, gross_amount, commission_amount,
                    net_amount, commission_rate_bps, status, created_at, released_at, paid_out_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1000, ?, ?, ?, ?, ?)
                """, c.earning, c.quotation, fixer, gross, commission, gross - commission, status, createdAt,
                status.equals("HELD") ? null : NOW, status.equals("PAID_OUT") ? NOW : null, NOW);
    }

    void cashSettlement(JdbcTemplate db, Case c, UUID owner, UUID fixer, long agreed, long received) {
        db.update("""
                INSERT INTO cash_settlements (id, job_id, quotation_id, request_id, owner_user_id, fixer_user_id,
                    agreed_amount, received_amount, outstanding_amount, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.settlement, UUID.randomUUID(), c.quotation, c.request, owner, fixer, agreed, received,
                agreed - received, NOW);
    }

    void debt(JdbcTemplate db, Case c, UUID owner, UUID fixer, long amount, String status) {
        db.update("""
                INSERT INTO owner_debts (id, owner_user_id, fixer_user_id, request_id, quotation_id, amount, status,
                    created_at, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.debt, owner, fixer, c.request, c.quotation, amount, status, NOW,
                status.equals("PAID") ? NOW : null);
    }

    private void payout(JdbcTemplate db, UUID id, UUID fixer, long amount, int count, UUID operationKey) {
        db.update("""
                INSERT INTO payouts (id, fixer_user_id, amount, earning_count, status, requested_at, transferred_at,
                    operation_key)
                VALUES (?, ?, ?, ?, 'TRANSFERRED', ?, ?, ?)
                """, id, fixer, amount, count, NOW, NOW, operationKey);
    }

    private void payoutEntry(JdbcTemplate db, UUID earning, UUID payout, UUID fixer, long net) {
        db.update("INSERT INTO payout_entries (earning_id, payout_id, fixer_user_id, net_amount) VALUES (?, ?, ?, ?)",
                earning, payout, fixer, net);
    }

    static OffsetDateTime now() {
        return NOW;
    }

    private static String reference(UUID paymentId) {
        return "FXR-" + paymentId.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
