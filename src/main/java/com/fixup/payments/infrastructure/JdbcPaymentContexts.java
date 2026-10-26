package com.fixup.payments.infrastructure;

import com.fixup.payments.api.PaymentContext;
import com.fixup.payments.domain.PaymentContexts;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPaymentContexts implements PaymentContexts {
    private final JdbcTemplate jdbc;
    JdbcPaymentContexts(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PaymentContext> findByQuotation(UUID quotationId) {
        return jdbc.query("SELECT * FROM repair_payment_contexts WHERE quotation_id = ?", (r, n) ->
                new PaymentContext(r.getObject("quotation_id", UUID.class), r.getObject("request_id", UUID.class),
                        r.getObject("property_id", UUID.class), r.getObject("owner_user_id", UUID.class),
                        r.getObject("fixer_user_id", UUID.class), r.getObject("manager_user_id", UUID.class),
                        r.getString("specialty"), r.getString("concept"),
                        r.getTimestamp("captured_at").toInstant(), r.getString("source")), quotationId)
                .stream().findFirst();
    }

    @Override
    public Map<UUID, PaymentContext> findByQuotations(Collection<UUID> quotationIds) {
        if (quotationIds == null || quotationIds.isEmpty()) {
            return Map.of();
        }
        var ids = quotationIds.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        var contexts = jdbc.query("SELECT * FROM repair_payment_contexts WHERE quotation_id IN (" + placeholders + ")",
                (r, n) -> new PaymentContext(r.getObject("quotation_id", UUID.class), r.getObject("request_id", UUID.class),
                        r.getObject("property_id", UUID.class), r.getObject("owner_user_id", UUID.class),
                        r.getObject("fixer_user_id", UUID.class), r.getObject("manager_user_id", UUID.class),
                        r.getString("specialty"), r.getString("concept"),
                        r.getTimestamp("captured_at").toInstant(), r.getString("source")), ids.toArray());
        var byQuotation = new LinkedHashMap<UUID, PaymentContext>();
        contexts.forEach(context -> byQuotation.put(context.quotationId(), context));
        return Map.copyOf(byQuotation);
    }

    public void create(PaymentContext c) {
        jdbc.update("""
                INSERT INTO repair_payment_contexts(quotation_id, request_id, property_id, owner_user_id,
                    fixer_user_id, manager_user_id, specialty, concept, captured_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.quotationId(), c.requestId(), c.propertyId(), c.ownerUserId(), c.fixerUserId(),
                c.managerUserId(), c.specialty(), c.concept(), Timestamp.from(c.capturedAt()), c.source());
    }
}
