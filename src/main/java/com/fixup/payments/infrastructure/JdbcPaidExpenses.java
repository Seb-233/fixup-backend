package com.fixup.payments.infrastructure;

import com.fixup.payments.api.ExpenseLedger.Expense;
import com.fixup.payments.domain.PaidExpenses;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only canonical financial facts; debt payments add the unpaid balance, never the full cash price. */
@Repository
class JdbcPaidExpenses implements PaidExpenses {
    private final JdbcTemplate jdbc;
    JdbcPaidExpenses(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Row> between(Instant from, Instant to) {
        return jdbc.query("""
                SELECT f.*, c.property_id, c.specialty, c.concept
                FROM (
                    SELECT id, quotation_id, request_id, owner_user_id, fixer_user_id, amount, completed_at AS paid_at
                    FROM repair_payments WHERE status = 'APPROVED'
                    UNION ALL
                    SELECT id, quotation_id, request_id, owner_user_id, fixer_user_id, received_amount, settled_at
                    FROM cash_settlements WHERE received_amount > 0
                ) f LEFT JOIN repair_payment_contexts c ON c.quotation_id = f.quotation_id
                    AND c.request_id = f.request_id AND c.owner_user_id = f.owner_user_id AND c.fixer_user_id = f.fixer_user_id
                WHERE f.paid_at >= ? AND f.paid_at < ? ORDER BY f.paid_at, f.id
                """, (r, n) -> new Row(r.getObject("owner_user_id", UUID.class),
                new Expense(r.getObject("id", UUID.class), r.getObject("property_id", UUID.class),
                        r.getObject("request_id", UUID.class), r.getString("specialty"), r.getString("concept"),
                        r.getLong("amount"), r.getObject("fixer_user_id", UUID.class), r.getTimestamp("paid_at").toInstant())),
                Timestamp.from(from), Timestamp.from(to));
    }
}
