package com.fixup.testsupport;

import com.fixup.shared.security.DatabaseActorContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared test database cleaner.
 * Enforces correct reverse foreign key dependency deletion order so that
 * all integration tests can run consecutively without integrity violations.
 */
@Component
public class IntegrationDatabaseCleaner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public IntegrationDatabaseCleaner(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void clean() {
        // A handful of tests call a @PreAuthorize-guarded use case directly, bypassing HTTP entirely,
        // to prove method security holds even then. DatabaseActorContext is normally only ever
        // cleared by the MVC interceptor once a real HTTP request finishes, so a direct call like
        // that leaves it set on the test thread with nothing to clear it afterwards. This must run
        // before the transaction below begins (a plain TransactionTemplate, not @Transactional: a
        // self-invoked @Transactional method would skip the AOP proxy and never actually start one),
        // or this cleanup -- and any RLS-protected table it touches -- could itself silently run as
        // fixup_app because of some earlier, unrelated test's leftover actor.
        DatabaseActorContext.clear();
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM notifications");
            jdbc.update("DELETE FROM sla_events_log");
            jdbc.update("DELETE FROM chat_messages");
            jdbc.update("DELETE FROM payouts");
            jdbc.update("DELETE FROM fixer_earnings");
            jdbc.update("DELETE FROM jobs");
            jdbc.update("DELETE FROM quotations");
            jdbc.update("DELETE FROM repair_request_photos");
            jdbc.update("DELETE FROM repair_requests");
            jdbc.update("DELETE FROM media_deletion_jobs");
            jdbc.update("DELETE FROM portfolio_pieces");
            jdbc.update("DELETE FROM fixer_portfolios");
            jdbc.update("DELETE FROM fixer_verification_documents");
            jdbc.update("DELETE FROM media_assets");
            jdbc.update("DELETE FROM market_indicator_snapshots");
            jdbc.update("DELETE FROM fixer_specialties");
            jdbc.update("DELETE FROM fixer_profiles");
            jdbc.update("DELETE FROM user_roles");
            jdbc.update("DELETE FROM properties");
            jdbc.update("DELETE FROM users");
        });
    }
}