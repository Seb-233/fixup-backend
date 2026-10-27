package com.fixup.testsupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Administrative test-only reconstruction of a job accepted BEFORE V41. No production endpoint bypass. */
public final class HistoricalCashFixtures {
    private HistoricalCashFixtures() { }
    /** A pre-cutover request with no accepted quotation cannot open a new CASH job today. */
    public static void openRequest(JdbcTemplate jdbc, UUID requestId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean old = connection.getAutoCommit(); connection.setAutoCommit(false);
            var scoped = new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                    if ("fixup_app".equals(scoped.queryForObject("SELECT current_user",String.class))) throw new IllegalStateException("Administrative fixture required");
                    scoped.execute("SET LOCAL session_replication_role=replica");
                }
                scoped.update("UPDATE repair_requests SET payment_method='CASH',legacy_cash=TRUE,created_at=TIMESTAMP '2020-01-01 00:00:00' WHERE id=?",requestId);
                connection.commit(); return null;
            } catch(Exception e) { connection.rollback(); throw e; }
            finally {connection.setAutoCommit(old);}
        });
    }
    public static UUID acceptedJob(JdbcTemplate jdbc, UUID quotationId) {
        return jdbc.execute((ConnectionCallback<UUID>) connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            var scoped = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                    if ("fixup_app".equals(scoped.queryForObject("SELECT current_user", String.class))) throw new IllegalStateException("Administrative fixture requires isolated test database owner");
                    scoped.execute("SET LOCAL session_replication_role = replica");
                }
                var q = scoped.queryForMap("SELECT q.request_id,q.fixer_user_id,q.amount,r.owner_user_id,r.property_id,r.manager_user_id,r.specialty,r.title FROM quotations q JOIN repair_requests r ON r.id=q.request_id WHERE q.id=?", quotationId);
                var cutoff = scoped.queryForObject("SELECT installed_on FROM flyway_schema_history WHERE version='41'", Timestamp.class).toInstant();
                var before = Timestamp.from(cutoff.minusSeconds(86400));
                var job = UUID.randomUUID();
                scoped.update("UPDATE repair_requests SET payment_method='CASH',legacy_cash=TRUE,status='ASSIGNED',assigned_fixer_user_id=?,created_at=?,updated_at=? WHERE id=?", q.get("fixer_user_id"), before,before,q.get("request_id"));
                scoped.update("UPDATE quotations SET status='ACCEPTED',created_at=?,updated_at=? WHERE id=?", before,before,quotationId);
                scoped.update("INSERT INTO jobs(id,request_id,quotation_id,fixer_user_id,owner_user_id,status,created_at,updated_at,payment_method,agreed_amount,legacy_cash) VALUES (?,?,?,?,?,'ASSIGNED',?,?,'CASH',?,TRUE)", job,q.get("request_id"),quotationId,q.get("fixer_user_id"),q.get("owner_user_id"),before,before,q.get("amount"));
                scoped.update("INSERT INTO job_status_log(id,job_id,status,note,changed_by_user_id,changed_at) VALUES (?,?,'ASSIGNED','Trabajo histórico',?,?)",UUID.randomUUID(),job,q.get("owner_user_id"),before);
                scoped.update("INSERT INTO repair_payment_contexts(quotation_id,request_id,property_id,owner_user_id,fixer_user_id,manager_user_id,specialty,concept,captured_at,source) VALUES (?,?,?,?,?,?,?,?,?,'LEGACY_REQUEST_CONTEXT')",quotationId,q.get("request_id"),q.get("property_id"),q.get("owner_user_id"),q.get("fixer_user_id"),q.get("manager_user_id"),q.get("specialty"),q.get("title"),Timestamp.from(Instant.now()));
                connection.commit();
                return job;
            } catch (Exception failure) { connection.rollback(); throw failure; }
            finally { connection.setAutoCommit(oldAutoCommit); }
        });
    }
}
