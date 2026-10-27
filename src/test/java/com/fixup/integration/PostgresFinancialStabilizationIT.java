package com.fixup.integration;

import com.fixup.payments.api.ExpenseLedger;
import com.fixup.payments.api.PaymentInitiation;
import com.fixup.payments.api.PaymentSettlement;
import com.fixup.payments.application.SimulateRepairPayment;
import com.fixup.payments.domain.PaymentGateway;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Full application startup against an isolated schema; no alternative financial entities. */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestJwtConfiguration.class, TestStorageConfiguration.class})
@org.springframework.test.annotation.DirtiesContext
@Testcontainers
class PostgresFinancialStabilizationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationContext context;
    @Autowired EntityManagerFactory entities;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;

    @Test
    void activeSchemaAndHibernateValidateWithoutArchivedTables() {
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(41);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = 'owner_payments'", Integer.class)).isZero();
        assertThat(entities.getMetamodel().getEntities()).noneSatisfy(entity ->
                assertThat(entity.getJavaType().getSimpleName()).isEqualTo("OwnerChargeEntity"));
        for (var entity : entities.getMetamodel().getEntities()) {
            var table = entity.getJavaType().getAnnotation(Table.class);
            if (table != null) {
                assertThat(table.name()).isNotEqualTo("owner_payments");
            }
        }
    }

    @Test
    void canonicalProcessorAndPublicContractsAreWiredWithoutAlternativeBeans() {
        assertThat(context.getBeansOfType(SimulateRepairPayment.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentInitiation.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentSettlement.class)).hasSize(1);
        assertThat(context.getBeansOfType(ExpenseLedger.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentGateway.class)).hasSize(1);
        assertThat(context.getBeanDefinitionNames()).doesNotContain("payCharge", "listOwnerCharges",
                "jpaOwnerCharges", "ownerChargeJpaRepository");
    }
}
