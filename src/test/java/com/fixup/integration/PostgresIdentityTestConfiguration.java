package com.fixup.integration;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only provisioning of the operational identity role, absent from the base migration series.
 * Does not alter application policies, ACLs or any migration history version.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresIdentityTestConfiguration {
    @Bean
    FlywayMigrationStrategy provisionTestIdentityRole() {
        return flyway -> {
            flyway.migrate();
            try (var c = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())) return;
                try (var statement = c.createStatement()) {
                    statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixup_identity') "
                            + "THEN CREATE ROLE fixup_identity NOLOGIN NOBYPASSRLS; END IF; END $$");
                    statement.execute("GRANT USAGE ON SCHEMA public TO fixup_identity");
                    statement.execute("GRANT SELECT ON users, user_roles TO fixup_identity");
                    statement.execute("GRANT INSERT ON users TO fixup_identity");
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("Cannot provision isolated identity test role", exception);
            }
        };
    }
}
