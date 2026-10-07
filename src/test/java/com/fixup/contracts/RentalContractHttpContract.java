package com.fixup.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.contracts.api.ContractExpiringSoon;
import com.fixup.contracts.application.ContractExpiryScheduler;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class RentalContractHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired IntegrationDatabaseCleaner databaseCleaner;
    @Autowired ContractExpiryScheduler scheduler;
    @Autowired(required = false) TestExpiringEventListener expiringEventListener;

    static class TestExpiringEventListener implements ApplicationListener<ApplicationEvent> {
        final List<ContractExpiringSoon> events = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof PayloadApplicationEvent<?> payloadEvent
                    && payloadEvent.getPayload() instanceof ContractExpiringSoon ces) {
                events.add(ces);
            }
        }

        void clear() {
            events.clear();
        }
    }

    @AfterEach
    void clearDatabase() {
        jdbc.update("DELETE FROM rental_contracts");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM properties");
        databaseCleaner.clean();
        if (expiringEventListener != null) {
            expiringEventListener.clear();
        }
    }

    private UUID seedUser(UUID userId, String auth0Id, String role) {
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            userId, auth0Id
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        return userId;
    }

    private UUID seedProperty(UUID propertyId, UUID ownerId) {
        jdbc.update(
            "INSERT INTO properties (id, owner_user_id, name, address, city, area_m2, created_at, updated_at) VALUES (?, ?, 'Prop', 'Addr', 'City', 100, NOW(), NOW())",
            propertyId, ownerId
        );
        return propertyId;
    }

    @Test
    void createsContractSuccessfully() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerAuth0Id = "auth0|owner";
        seedUser(ownerId, ownerAuth0Id, "OWNER");

        UUID tenantId = UUID.randomUUID();
        seedUser(tenantId, "auth0|tenant", "TENANT");

        UUID propertyId = UUID.randomUUID();
        seedProperty(propertyId, ownerId);

        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusMonths(12);

        String json = """
            {
              "propertyId": "%s",
              "tenantUserId": "%s",
              "startDate": "%s",
              "endDate": "%s",
              "monthlyRentAmount": 1500000,
              "depositAmount": 3000000,
              "paymentDayOfMonth": 5,
              "notes": "Standard 12-month lease"
            }
            """.formatted(propertyId, tenantId, start, end);

        String response = mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.propertyId").value(propertyId.toString()))
            .andExpect(jsonPath("$.monthlyRentAmount").value(1500000))
            .andExpect(jsonPath("$.paymentDayOfMonth").value(5))
            .andReturn().getResponse().getContentAsString();

        String idStr = mapper.readTree(response).get("id").asText();

        mvc.perform(get("/contracts/" + idStr)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(idStr))
            .andExpect(jsonPath("$.notes").value("Standard 12-month lease"));
    }

    @Test
    void rejectsOverlappingContract() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerAuth0Id = "auth0|owner";
        seedUser(ownerId, ownerAuth0Id, "OWNER");

        UUID tenant1Id = UUID.randomUUID();
        seedUser(tenant1Id, "auth0|tenant1", "TENANT");
        UUID tenant2Id = UUID.randomUUID();
        seedUser(tenant2Id, "auth0|tenant2", "TENANT");

        UUID propertyId = UUID.randomUUID();
        seedProperty(propertyId, ownerId);

        LocalDate start1 = LocalDate.now().plusDays(1);
        LocalDate end1 = start1.plusMonths(6);

        String firstJson = """
            {
              "propertyId": "%s",
              "tenantUserId": "%s",
              "startDate": "%s",
              "endDate": "%s",
              "monthlyRentAmount": 1000000,
              "depositAmount": 2000000,
              "paymentDayOfMonth": 1,
              "notes": "First contract"
            }
            """.formatted(propertyId, tenant1Id, start1, end1);

        mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstJson)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated());

        LocalDate start2 = start1.plusMonths(3);
        LocalDate end2 = start2.plusMonths(6);

        String overlapJson = """
            {
              "propertyId": "%s",
              "tenantUserId": "%s",
              "startDate": "%s",
              "endDate": "%s",
              "monthlyRentAmount": 1200000,
              "depositAmount": 2400000,
              "paymentDayOfMonth": 15,
              "notes": "Overlapping contract"
            }
            """.formatted(propertyId, tenant2Id, start2, end2);

        mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(overlapJson)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONTRACT_OVERLAP"));
    }

    @Test
    void schedulerTriggersExpiringSoonEvent() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerAuth0Id = "auth0|owner";
        seedUser(ownerId, ownerAuth0Id, "OWNER");

        UUID tenantId = UUID.randomUUID();
        seedUser(tenantId, "auth0|tenant", "TENANT");

        UUID propertyId = UUID.randomUUID();
        seedProperty(propertyId, ownerId);

        UUID contractId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 6, 1);
        LocalDate start = today.minusMonths(6);
        LocalDate endIn30Days = today.plusDays(30);

        jdbc.update("""
            INSERT INTO rental_contracts
            (id, property_id, owner_user_id, tenant_user_id, start_date, end_date,
             monthly_rent_amount, deposit_amount, payment_day_of_month, status,
             renewal_notice_days, notes, created_at, updated_at,
             reminder_30d_sent, reminder_7d_sent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 30, 'Notes', NOW(), NOW(), FALSE, FALSE)
            """,
            contractId, propertyId, ownerId, tenantId, start, endIn30Days,
            1000000L, 2000000L, 1
        );

        scheduler.processDay(today);

        if (expiringEventListener != null) {
            List<ContractExpiringSoon> events = new ArrayList<>(expiringEventListener.events);
            boolean has30DEvent = events.stream()
                .anyMatch(e -> "30D".equals(e.type()) && contractId.equals(e.contractId()));
            assertTrue(has30DEvent, "Expected 30D expiring event, got: " + events);
        }

        Boolean reminderSent = jdbc.queryForObject(
            "SELECT reminder_30d_sent FROM rental_contracts WHERE id = ?",
            Boolean.class, contractId
        );
        assertTrue(reminderSent != null && reminderSent, "Reminder 30D flag should be set");
    }

    @Test
    void renewMovesStatusToRenewed() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ownerAuth0Id = "auth0|owner";
        seedUser(ownerId, ownerAuth0Id, "OWNER");

        UUID tenantId = UUID.randomUUID();
        seedUser(tenantId, "auth0|tenant", "TENANT");

        UUID propertyId = UUID.randomUUID();
        seedProperty(propertyId, ownerId);

        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = start.plusMonths(6);

        String createJson = """
            {
              "propertyId": "%s",
              "tenantUserId": "%s",
              "startDate": "%s",
              "endDate": "%s",
              "monthlyRentAmount": 800000,
              "depositAmount": 1600000,
              "paymentDayOfMonth": 10,
              "notes": "Short term"
            }
            """.formatted(propertyId, tenantId, start, end);

        String response = mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createJson)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andReturn().getResponse().getContentAsString();

        String contractId = mapper.readTree(response).get("id").asText();
        LocalDate newEnd = end.plusMonths(6);

        String renewJson = """
            {"newEndDate": "%s"}
            """.formatted(newEnd);

        mvc.perform(post("/contracts/" + contractId + "/renew")
                .contentType(MediaType.APPLICATION_JSON)
                .content(renewJson)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RENEWED"))
            .andExpect(jsonPath("$.endDate").value(newEnd.toString()));

        mvc.perform(get("/contracts/" + contractId)
                .with(jwt().jwt(j -> j.subject(ownerAuth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RENEWED"));

        Boolean reminder30Reset = jdbc.queryForObject(
            "SELECT reminder_30d_sent FROM rental_contracts WHERE id = ?",
            Boolean.class, UUID.fromString(contractId)
        );
        assertFalse(reminder30Reset != null && reminder30Reset,
            "Reminder flags should be reset on renew");
    }
}
