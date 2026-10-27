package com.fixup.integration;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Upgrade of populated V38 in public, preserving checksums, financial facts and old audit rows. */
@Testcontainers
class PostgresDigitalPaymentMigrationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Test void upgradesExistingFinancialFactsWithoutInventingAttemptMetadata() {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:db/migration-postgres/postgresql");
        assertThat(configuration.target("38").load().migrate().migrationsExecuted).isEqualTo(37);
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var history = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        UUID owner = UUID.randomUUID(), fixer = UUID.randomUUID(), property = UUID.randomUUID(), request = UUID.randomUUID(), quote = UUID.randomUUID(), payment = UUID.randomUUID();
        for (UUID id : List.of(owner, fixer)) jdbc.update("INSERT INTO users(id,auth0_subject,status,created_at,updated_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, "migration|" + id);
        jdbc.update("INSERT INTO user_roles(user_id,role) VALUES (?,'OWNER'),(?,'FIXER')", owner, fixer);
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, property, owner);
        jdbc.update("""
                INSERT INTO repair_requests(id,owner_user_id,specialty,title,description,status,assigned_fixer_user_id,
                    created_at,updated_at,property_id,payment_method,requested_by_user_id)
                VALUES (?,?,'PLUMBING','Historical repair','Historical description','ASSIGNED',?,
                    TIMESTAMPTZ '2025-01-01 00:00:00Z',TIMESTAMPTZ '2025-01-01 00:00:00Z',?,'TRANSFER',?)
                """, request, owner, fixer, property, owner);
        jdbc.update("INSERT INTO quotations(id,request_id,fixer_user_id,amount,estimated_days,status,created_at,updated_at) VALUES (?,?,?,100000,1,'ACCEPTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", quote, request, fixer);
        jdbc.update("""
                INSERT INTO repair_payments(id,quotation_id,request_id,owner_user_id,fixer_user_id,amount,payment_method,purpose,status,created_at,updated_at,completed_at)
                VALUES (?,?,?,?,?,100000,'TRANSFER','SERVICE','APPROVED',TIMESTAMPTZ '2025-01-01 00:00:00Z',TIMESTAMPTZ '2025-01-02 00:00:00Z',TIMESTAMPTZ '2025-01-02 00:00:00Z')
                """, payment, quote, request, owner, fixer);
        jdbc.update("INSERT INTO simulated_payment_attempts(id,payment_id,requested_outcome,resulting_status,attempted_by_user_id,attempted_at) VALUES (?,?,'APPROVED','APPROVED',?,TIMESTAMPTZ '2025-01-02 00:00:00Z')", UUID.randomUUID(), payment, owner);
        var oldPayment = jdbc.queryForMap("SELECT * FROM repair_payments");
        var oldAttempt = jdbc.queryForMap("SELECT * FROM simulated_payment_attempts");
        var flyway = configuration.target("40").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(39);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE installed_rank <= 37 ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForMap("SELECT * FROM repair_payments")).containsAllEntriesOf(oldPayment);
        assertThat(jdbc.queryForMap("SELECT * FROM simulated_payment_attempts")).containsAllEntriesOf(oldAttempt);
        assertThat(jdbc.queryForObject("SELECT reference FROM repair_payments", String.class)).isEqualTo("FXR-" + payment.toString().replace("-", "").toUpperCase(Locale.ROOT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM simulated_payment_attempts WHERE operation_key IS NULL AND instrument IS NULL AND gateway_transaction_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM repair_payment_contexts", String.class)).isEqualTo("LEGACY_REQUEST_CONTEXT");
        assertThat(jdbc.queryForObject("SELECT property_id FROM repair_payment_contexts", UUID.class)).isEqualTo(property);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repair_payment_contexts WHERE captured_at > TIMESTAMPTZ '2025-01-02 00:00:00Z'", Integer.class)).isEqualTo(1);
        var v40History = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
        // Genuine pre-cutover data, inserted before V41 rather than using an application bypass.
        UUID cashRequest=UUID.randomUUID(),cashQuote=UUID.randomUUID(),cashJob=UUID.randomUUID(),oldEarning=UUID.randomUUID();
        jdbc.update("INSERT INTO repair_requests(id,owner_user_id,specialty,title,description,status,assigned_fixer_user_id,created_at,updated_at,property_id,payment_method,requested_by_user_id) VALUES (?,?,'PLUMBING','Old cash','Historical','ASSIGNED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,'CASH',?)",cashRequest,owner,fixer,property,owner);
        jdbc.update("INSERT INTO quotations(id,request_id,fixer_user_id,amount,estimated_days,status,created_at,updated_at) VALUES (?,?,?,100000,1,'ACCEPTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",cashQuote,cashRequest,fixer);
        jdbc.update("INSERT INTO jobs(id,request_id,quotation_id,fixer_user_id,owner_user_id,status,payment_method,agreed_amount,created_at,updated_at) VALUES (?,?,?,?,?,'ASSIGNED','CASH',100000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",cashJob,cashRequest,cashQuote,fixer,owner);
        jdbc.update("INSERT INTO fixer_earnings(id,quotation_id,fixer_user_id,gross_amount,commission_amount,net_amount,commission_rate_bps,status,created_at,released_at,paid_out_at,updated_at) VALUES (?,?,?,100000,17500,82500,1750,'PAID_OUT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",oldEarning,quote,fixer);
        jdbc.update("INSERT INTO payouts(id,fixer_user_id,amount,earning_count,status,requested_at,transferred_at) VALUES (?,?,82500,1,'TRANSFERRED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID(),fixer);
        var historicalEarning=jdbc.queryForMap("SELECT * FROM fixer_earnings");
        var historicalPayout=jdbc.queryForMap("SELECT * FROM payouts");
        var historicalJob=jdbc.queryForMap("SELECT * FROM jobs");
        var latest = configuration.target("latest").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        latest.validate();
        assertThat(latest.info().applied()).hasSize(41);
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE installed_rank <= 39 ORDER BY installed_rank")).isEqualTo(v40History);
        assertThat(jdbc.queryForMap("SELECT * FROM fixer_earnings")).isEqualTo(historicalEarning);
        assertThat(jdbc.queryForMap("SELECT * FROM payouts")).containsAllEntriesOf(historicalPayout).containsEntry("operation_key",null);
        assertThat(jdbc.queryForMap("SELECT * FROM jobs")).containsAllEntriesOf(historicalJob).containsEntry("legacy_cash",true);
        assertThat(jdbc.queryForObject("SELECT legacy_cash FROM repair_requests WHERE id=?",Boolean.class,cashRequest)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_entries",Integer.class)).isZero();
        assertThat(jdbc.update("UPDATE jobs SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP WHERE id=?",cashJob)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO jobs(id,request_id,quotation_id,fixer_user_id,owner_user_id,status,payment_method,agreed_amount,created_at,updated_at) VALUES (?,?,?,?,?,'ASSIGNED','CASH',100000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID(),request,quote,fixer,owner)).hasStackTraceContaining("DIGITAL_PAYMENT_REQUIRED");
        assertThatThrownBy(() -> jdbc.update("UPDATE repair_payments SET amount = 1")).hasStackTraceContaining("immutable");
    }
}
