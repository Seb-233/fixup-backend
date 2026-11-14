package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.domain.WompiCheckout;
import com.fixup.payments.domain.WompiCheckouts;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWompiCheckouts implements WompiCheckouts {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    JdbcWompiCheckouts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void create(WompiCheckout c) {
        jdbc.update("""
                INSERT INTO wompi_checkouts(reference, payment_id, amount_in_cents, created_by_user_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, c.reference(), c.paymentId(), c.amountInCents(), c.createdByUserId(),
                Timestamp.from(c.createdAt()));
    }

    @Override
    public Optional<WompiCheckout> findByReference(String reference) {
        return jdbc.query("SELECT * FROM wompi_checkouts WHERE reference = ?", (r, n) -> new WompiCheckout(
                r.getString("reference"), r.getObject("payment_id", UUID.class), r.getLong("amount_in_cents"),
                r.getObject("created_by_user_id", UUID.class), r.getTimestamp("created_at").toInstant()),
                reference).stream().findFirst();
    }

    @Override
    public Map<String, CheckoutPayment> paymentsByReference(Collection<String> references) {
        var result = new HashMap<String, CheckoutPayment>();
        if (references.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT c.reference, c.amount_in_cents, p.id, p.reference AS payment_reference, p.purpose, p.status
                FROM wompi_checkouts c JOIN repair_payments p ON p.id = c.payment_id
                WHERE c.reference IN (:references)
                """, Map.of("references", references), row -> {
            result.put(row.getString("reference"), new CheckoutPayment(row.getString("reference"),
                    row.getLong("amount_in_cents"), row.getObject("id", UUID.class), row.getString("payment_reference"),
                    row.getString("purpose"), PaymentStatus.valueOf(row.getString("status"))));
        });
        return result;
    }

    @Override
    public Map<String, PaymentStatus> recordedTransactions(Collection<String> transactionIds) {
        var result = new HashMap<String, PaymentStatus>();
        if (transactionIds.isEmpty()) {
            return result;
        }
        named.query("""
                SELECT gateway_transaction_id, resulting_status FROM simulated_payment_attempts
                WHERE instrument = 'WOMPI' AND gateway_transaction_id IN (:ids)
                """, Map.of("ids", transactionIds), row -> {
            result.put(row.getString("gateway_transaction_id"), PaymentStatus.valueOf(row.getString("resulting_status")));
        });
        return result;
    }
}
