package com.fixup.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared test database cleaner.
 * Enforces correct reverse foreign key dependency deletion order so that
 * all integration tests can run consecutively without integrity violations.
 */
@Component
public class IntegrationDatabaseCleaner {
    private final JdbcTemplate jdbc;

    public IntegrationDatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void clean() {
        jdbc.update("DELETE FROM quotations");
        jdbc.update("DELETE FROM repair_request_photos");
        jdbc.update("DELETE FROM repair_requests");
        jdbc.update("DELETE FROM fixer_verification_documents");
        jdbc.update("DELETE FROM fixer_specialties");
        jdbc.update("DELETE FROM fixer_profiles");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
    }
}
