package com.fixup.requests;

import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.Role;
import com.fixup.notifications.api.NotificationType;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.application.SlaCheckScheduler;
import com.fixup.requests.domain.RepairRequest;
import com.fixup.requests.domain.RepairRequests;
import com.fixup.requests.domain.SlaPolicy;
import com.fixup.shared.security.DatabaseActorContext;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.fixup.integration.TestStorageConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba RNF-07 contra PostgreSQL 17: persistencia del plazo de 48 horas, transiciones de aviso e
 * incumplimiento, auditoría y notificaciones guardadas para el administrador de la plataforma.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestStorageConfiguration.class, PostgresSlaRnf07IT.FixedClockConfiguration.class})
@DirtiesContext
@Testcontainers
class PostgresSlaRnf07IT {

    private static final Instant NOW = Instant.parse("2025-07-01T10:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROPERTY_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RepairRequests requests;
    @Autowired SlaCheckScheduler scheduler;
    @Autowired IntegrationDatabaseCleaner databaseCleaner;

    @BeforeEach
    void preparePostgresFixtures() {
        DatabaseActorContext.clear();
        databaseCleaner.clean();
        insertUser(OWNER_ID, "rnf07-owner", "Propietario RNF-07");
        insertUser(ADMIN_ID, "rnf07-admin", "Administrador RNF-07");
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, 'OWNER'), (?, 'PLATFORM_ADMIN')",
                OWNER_ID, ADMIN_ID);
        jdbc.update("""
                INSERT INTO properties(id, owner_user_id, name, address, city, area_m2, created_at, updated_at)
                VALUES (?, ?, 'Inmueble RNF-07', 'Calle de prueba 1', 'Bogotá', 75.00, ?, ?)
                """, PROPERTY_ID, OWNER_ID, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void urgentOpeningPersistsDeadlineExactlyFortyEightHoursLater() {
        RepairRequest request = openUrgentAt(NOW);
        requests.create(request);

        assertThat(request.slaDeadline()).isEqualTo(NOW.plus(SlaPolicy.URGENT_DURATION));
        assertThat(jdbc.queryForObject("""
                SELECT extract(epoch FROM (sla_deadline - created_at))::bigint
                FROM repair_requests WHERE id = ?
                """, Long.class, request.id())).isEqualTo(48L * 60 * 60);
    }

    @Test
    void reachesWarningThresholdPersistsStateAndNotifiesPlatformAdmin() {
        Instant openedAt = NOW.minus(SlaPolicy.URGENT_DURATION).plus(SlaPolicy.WARNING_THRESHOLD);
        RepairRequest request = openUrgentAt(openedAt);
        requests.create(request);

        scheduler.checkSlas();

        assertThat(persistedStatus(request.id())).isEqualTo(RepairRequestStatus.SLA_WARNING.name());
        assertThat(notificationCount(ADMIN_ID, request.id(), NotificationType.REQUEST_SLA_WARNING)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM status_change_log
                WHERE resource_id = ? AND previous_status = 'OPEN' AND new_status = 'SLA_WARNING'
                """, Integer.class, request.id())).isEqualTo(1);
    }

    @Test
    void reachesFortyEightHoursPersistsBreachAuditAndAdminNotification() {
        RepairRequest request = openUrgentAt(NOW.minus(SlaPolicy.URGENT_DURATION));
        requests.create(request);

        scheduler.checkSlas();

        assertThat(persistedStatus(request.id())).isEqualTo(RepairRequestStatus.SLA_BREACHED.name());
        assertThat(jdbc.queryForObject("""
                SELECT last_escalation_notified_at = ?
                FROM repair_requests WHERE id = ?
                """, Boolean.class, Timestamp.from(NOW), request.id())).isTrue();
        assertThat(notificationCount(ADMIN_ID, request.id(), NotificationType.REQUEST_SLA_BREACHED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM status_change_log
                WHERE resource_id = ? AND previous_status = 'OPEN' AND new_status = 'SLA_BREACHED'
                """, Integer.class, request.id())).isEqualTo(1);
    }

    @Test
    void warningRequestTransitionsToBreachWhenFortyEightHoursExpire() {
        RepairRequest warning = openUrgentAt(NOW.minus(SlaPolicy.URGENT_DURATION))
                .withStatus(RepairRequestStatus.SLA_WARNING, NOW.minusSeconds(60));
        requests.create(warning);

        scheduler.checkSlas();

        assertThat(persistedStatus(warning.id())).isEqualTo(RepairRequestStatus.SLA_BREACHED.name());
        assertThat(notificationCount(ADMIN_ID, warning.id(), NotificationType.REQUEST_SLA_BREACHED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM status_change_log
                WHERE resource_id = ? AND previous_status = 'SLA_WARNING' AND new_status = 'SLA_BREACHED'
                """, Integer.class, warning.id())).isEqualTo(1);
    }

    private RepairRequest openUrgentAt(Instant openedAt) {
        return RepairRequest.open(UUID.randomUUID(),
                new RepairRequest.PropertyContext(PROPERTY_ID, "Bogotá", "Inmueble RNF-07",
                        "Calle de prueba 1", OWNER_ID, null),
                OWNER_ID, Specialty.PLUMBING, "Fuga urgente", "Fuga de agua en el baño",
                List.of(), UrgencyLevel.URGENT, PaymentMethod.TRANSFER, openedAt);
    }

    private void insertUser(UUID id, String subject, String displayName) {
        jdbc.update("""
                INSERT INTO users(id, auth0_subject, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, subject, subject + "@example.test", displayName,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private String persistedStatus(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM repair_requests WHERE id = ?", String.class, requestId);
    }

    private int notificationCount(UUID recipientId, UUID requestId, NotificationType type) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE recipient_user_id = ? AND entity_id = ? AND type = ?
                """, Integer.class, recipientId, requestId, type.name());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
