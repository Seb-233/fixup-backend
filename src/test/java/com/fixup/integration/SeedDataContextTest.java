package com.fixup.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los datos de demostración cargan completos contra el esquema real y quedan coherentes entre sí:
 * cada pago aprobado corresponde a un trabajo, cada calificación a un trabajo terminado y cada foto
 * sembrada termina en el almacén.
 */
@SpringBootTest(properties = "fixup.seed.enabled=true")
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@DirtiesContext
class SeedDataContextTest {
    @Autowired JdbcTemplate jdbc;

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void hundredsOfColombianRecordsLoadConsistently() {
        assertThat(count("SELECT count(*) FROM users")).isGreaterThan(150);
        assertThat(count("SELECT count(*) FROM properties WHERE status = 'PUBLISHED'")).isGreaterThan(100);
        assertThat(count("SELECT count(*) FROM repair_requests")).isGreaterThan(300);
        assertThat(count("SELECT count(*) FROM chat_messages")).isGreaterThan(500);
        assertThat(count("SELECT count(*) FROM properties WHERE city = 'Bogotá'")).isGreaterThan(30);

        // FR-UC-05: a TRANSFER job exists only with its approved payment; a completed CASH job has its
        // settlement, and every debt is the balance that settlement left.
        assertThat(count("SELECT count(*) FROM jobs WHERE payment_method = 'CASH'")).isGreaterThan(20);
        assertThat(count("SELECT count(*) FROM jobs j WHERE j.payment_method = 'TRANSFER' AND NOT EXISTS "
                + "(SELECT 1 FROM repair_payments p WHERE p.quotation_id = j.quotation_id AND p.purpose = 'SERVICE' "
                + "AND p.status = 'APPROVED')")).isZero();
        assertThat(count("SELECT count(*) FROM jobs j WHERE j.payment_method = 'CASH' AND j.status = 'COMPLETED' "
                + "AND NOT EXISTS (SELECT 1 FROM cash_settlements s WHERE s.job_id = j.id)")).isZero();
        assertThat(count("SELECT count(*) FROM owner_debts d WHERE NOT EXISTS (SELECT 1 FROM cash_settlements s "
                + "WHERE s.quotation_id = d.quotation_id AND s.outstanding_amount = d.amount)")).isZero();
        assertThat(count("SELECT count(*) FROM owner_debts WHERE status = 'OUTSTANDING'")).isPositive();
        // The protagonist owner is never blocked by a debt during the demo.
        assertThat(count("SELECT count(*) FROM owner_debts d JOIN users u ON u.id = d.owner_user_id "
                + "WHERE u.auth0_subject = 'auth0|demo-owner' AND d.status = 'OUTSTANDING'")).isZero();
        assertThat(count("SELECT count(*) FROM repair_requests r WHERE r.status = 'ASSIGNED' AND NOT EXISTS "
                + "(SELECT 1 FROM jobs j WHERE j.request_id = r.id)")).isPositive();
        // Every completed job has its line in the property history.
        assertThat(count("SELECT count(*) FROM jobs j WHERE j.status = 'COMPLETED' AND NOT EXISTS "
                + "(SELECT 1 FROM property_work_history w WHERE w.job_id = j.id)")).isZero();
        // Ratings only on completed jobs, and the fixer average matches them.
        assertThat(count("SELECT count(*) FROM service_ratings r JOIN jobs j ON j.id = r.job_id "
                + "WHERE j.status <> 'COMPLETED'")).isZero();
        assertThat(count("SELECT count(*) FROM fixer_profiles f WHERE f.rating_count <> "
                + "(SELECT count(*) FROM service_ratings r WHERE r.fixer_user_id = f.user_id)")).isZero();
        // Only verified fixers of the request's specialty were assigned.
        assertThat(count("SELECT count(*) FROM repair_requests r WHERE r.assigned_fixer_user_id IS NOT NULL AND NOT EXISTS "
                + "(SELECT 1 FROM fixer_specialties s JOIN fixer_profiles f ON f.user_id = s.fixer_user_id "
                + "WHERE s.fixer_user_id = r.assigned_fixer_user_id AND s.specialty = r.specialty "
                + "AND f.verification_status = 'VERIFIED')")).isZero();
        // Nothing happens in the future except scheduled work and visits.
        assertThat(count("SELECT count(*) FROM repair_requests WHERE created_at > CURRENT_TIMESTAMP")).isZero();
        assertThat(count("SELECT count(*) FROM quotations WHERE created_at > CURRENT_TIMESTAMP")).isZero();
        assertThat(count("SELECT count(*) FROM chat_messages WHERE sent_at > CURRENT_TIMESTAMP")).isZero();

        // The demo protagonists exist with their roles and data.
        assertThat(count("SELECT count(*) FROM users WHERE auth0_subject = 'auth0|demo-owner'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM properties p JOIN users u ON u.id = p.owner_user_id "
                + "WHERE u.auth0_subject = 'auth0|demo-owner'")).isGreaterThanOrEqualTo(8);
        assertThat(count("SELECT count(*) FROM fixer_profiles f JOIN users u ON u.id = f.user_id "
                + "WHERE u.auth0_subject = 'auth0|demo-fixer' AND f.verification_status = 'VERIFIED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rental_contracts c JOIN users u ON u.id = c.tenant_user_id "
                + "WHERE u.auth0_subject = 'auth0|demo-tenant' AND c.status = 'ACTIVE'")).isEqualTo(1);

        // Visits respect the business rule the app enforces: 7:00 to 19:00 Colombia time, and the open ones are ahead.
        var bogota = java.time.ZoneId.of("America/Bogota");
        var visitStarts = jdbc.query("SELECT scheduled_at, duration_minutes FROM technical_visits", (rs, row) ->
                new java.time.ZonedDateTime[] {
                    rs.getObject("scheduled_at", java.time.OffsetDateTime.class).atZoneSameInstant(bogota),
                    rs.getObject("scheduled_at", java.time.OffsetDateTime.class).atZoneSameInstant(bogota)
                            .plusMinutes(rs.getInt("duration_minutes"))});
        assertThat(visitStarts).isNotEmpty().allSatisfy(window -> {
            assertThat(window[0].getHour()).isBetween(7, 18);
            assertThat(window[1].toLocalTime()).isBeforeOrEqualTo(java.time.LocalTime.of(19, 0));
        });
        assertThat(count("SELECT count(*) FROM technical_visits WHERE status IN ('PROPOSED', 'CONFIRMED') "
                + "AND scheduled_at < CURRENT_TIMESTAMP")).isZero();
        assertThat(count("SELECT count(*) FROM technical_visits v JOIN repair_requests r ON r.id = v.request_id "
                + "WHERE v.status IN ('PROPOSED', 'CONFIRMED') AND r.status IN ('COMPLETED', 'CANCELLED')")).isZero();

        // Every seeded photo reached the storage.
        var keys = jdbc.queryForList("SELECT object_key FROM media_assets WHERE object_key LIKE 'seed/%'", String.class);
        assertThat(keys).hasSizeGreaterThan(500);
        assertThat(keys).allMatch(key -> TestStorageConfiguration.instance().exists(key));
    }
}
