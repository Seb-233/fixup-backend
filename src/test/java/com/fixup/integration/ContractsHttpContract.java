package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for FR-UC-14 (administrar contratos de arrendamiento).
 *
 * <p>Runs twice: against H2 in the fast suite ({@code ContractsContextTest}) and against a real
 * PostgreSQL through Testcontainers ({@code PostgresContractsIT}), like every other module's
 * contract in this repository.
 *
 * <p>Covers the three ASR that the SAD declares as high priority for this use case:
 * ASR-SE-09 (authorization by resource ownership), ASR-SE-10 (listing filters must agree across
 * roles) and ASR-SE-11 (the audit trail names the actor who really executed the action).
 */
abstract class ContractsHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;

    private static final LocalDate START = LocalDate.now().plusMonths(1);
    private static final LocalDate END = LocalDate.now().plusMonths(13);

    @AfterEach
    void clearDatabase() {
        jdbc.update("DELETE FROM lease_contracts");
        databaseCleaner.clean();
    }

    private UUID seedUser(String auth0Id, String role) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'N', 'ACTIVE', NOW(), NOW())",
            userId, auth0Id, auth0Id.replace('|', '.') + "@b.com"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role);
        return userId;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(
            String auth0Id, String role) {
        return jwt().jwt(j -> j.subject(auth0Id).claim("roles", role));
    }

    private String newContractBody(UUID propertyId, UUID tenantUserId, UUID managerUserId) {
        String manager = managerUserId == null ? "null" : "\"" + managerUserId + "\"";
        return """
            {
              "propertyId": "%s",
              "tenantUserId": "%s",
              "realEstateManagerUserId": %s,
              "startDate": "%s",
              "endDate": "%s",
              "monthlyRent": 2500000,
              "securityDeposit": 2500000,
              "paymentFrequency": "MONTHLY",
              "paymentDayOfMonth": 5,
              "currency": "COP",
              "contractTerms": "Arriendo de vivienda urbana.",
              "mediaIds": [],
              "clauses": "No se permiten mascotas sin autorizacion escrita."
            }
            """.formatted(propertyId, tenantUserId, manager, START, END);
    }

    private String createContract(String ownerAuth0Id, UUID propertyId, UUID tenantUserId,
            UUID managerUserId) throws Exception {
        String response = mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newContractBody(propertyId, tenantUserId, managerUserId))
                .with(as(ownerAuth0Id, "OWNER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.ownerSigned").value(false))
            .andExpect(jsonPath("$.tenantSigned").value(false))
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("id").asText();
    }

    @Test
    void walksTheWholeSignatureLifecycle() throws Exception {
        UUID ownerId = seedUser("auth0|contract-owner", "OWNER");
        UUID tenantId = seedUser("auth0|contract-tenant", "TENANT");
        UUID propertyId = UUID.randomUUID();

        String contractId = createContract("auth0|contract-owner", propertyId, tenantId, null);

        mvc.perform(patch("/contracts/" + contractId + "/send-for-tenant-signature")
                .with(as("auth0|contract-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_TENANT_SIGNATURE"));

        mvc.perform(patch("/contracts/" + contractId + "/sign-as-tenant")
                .with(as("auth0|contract-tenant", "TENANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING_OWNER_SIGNATURE"))
            .andExpect(jsonPath("$.tenantSigned").value(true))
            .andExpect(jsonPath("$.signedByTenantUserId").value(tenantId.toString()));

        // El contrato empieza dentro de un mes, asi que firmar no lo activa todavia: queda SIGNED.
        mvc.perform(patch("/contracts/" + contractId + "/sign-as-owner")
                .with(as("auth0|contract-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SIGNED"))
            .andExpect(jsonPath("$.ownerSigned").value(true))
            .andExpect(jsonPath("$.signedByOwnerUserId").value(ownerId.toString()));

        mvc.perform(get("/contracts/" + contractId)
                .with(as("auth0|contract-tenant", "TENANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    @Test
    void rejectsCreationByATenant() throws Exception {
        seedUser("auth0|only-tenant", "TENANT");
        UUID otherTenant = seedUser("auth0|other-tenant", "TENANT");

        mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newContractBody(UUID.randomUUID(), otherTenant, null))
                .with(as("auth0|only-tenant", "TENANT")))
            .andExpect(status().isForbidden());
        // El inquilino sigue sin contratos propios.
        mvc.perform(get("/contracts/me").with(as("auth0|only-tenant", "TENANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void rejectsAContractWhereOwnerAndTenantAreTheSamePerson() throws Exception {
        UUID ownerId = seedUser("auth0|self-contract", "OWNER");

        mvc.perform(post("/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(newContractBody(UUID.randomUUID(), ownerId, null))
                .with(as("auth0|self-contract", "OWNER")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SAME_OWNER_AND_TENANT"));
    }

    /** ASR-SE-09: un tercero no puede leer un contrato ajeno y el sistema no revela su contenido. */
    @Test
    void hidesTheContractFromAThirdParty() throws Exception {
        seedUser("auth0|owner-a", "OWNER");
        UUID tenantId = seedUser("auth0|tenant-a", "TENANT");
        seedUser("auth0|owner-b", "OWNER");

        String contractId = createContract("auth0|owner-a", UUID.randomUUID(), tenantId, null);

        mvc.perform(get("/contracts/" + contractId).with(as("auth0|owner-b", "OWNER")))
            .andExpect(status().isForbidden());

        mvc.perform(get("/contracts/me").with(as("auth0|owner-b", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * ASR-SE-10: el mismo contrato debe aparecer con cualquier rol consultado y con cualquier
     * combinacion de filtros que lo contenga. La medida del ASR es el conteo de filas.
     */
    @Test
    void listsTheSameContractWhicheverRoleIsQueried() throws Exception {
        seedUser("auth0|list-owner", "OWNER");
        UUID tenantId = seedUser("auth0|list-tenant", "TENANT");
        UUID propertyId = UUID.randomUUID();

        String contractId = createContract("auth0|list-owner", propertyId, tenantId, null);

        String[] ownerQueries = {
            "/contracts/me",
            "/contracts/me?role=OWNER",
            "/contracts/me?role=ANY",
            "/contracts/me?status=DRAFT",
            "/contracts/me?propertyId=" + propertyId,
            "/contracts/me?role=OWNER&status=DRAFT&propertyId=" + propertyId,
            "/contracts/me?startFrom=" + START.minusDays(1) + "&endUntil=" + END.plusDays(1)
        };
        for (String query : ownerQueries) {
            mvc.perform(get(query).with(as("auth0|list-owner", "OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(contractId));
        }

        mvc.perform(get("/contracts/me?role=TENANT").with(as("auth0|list-tenant", "TENANT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(contractId));

        // Un filtro que no corresponde al contrato no debe devolverlo.
        mvc.perform(get("/contracts/me?propertyId=" + UUID.randomUUID())
                .with(as("auth0|list-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * ASR-SE-11: cuando el gestor inmobiliario cancela en nombre del propietario, el registro
     * queda con el identificador del gestor y no con uno genérico ni con el del propietario.
     */
    @Test
    void recordsTheRealActorWhenAManagerCancels() throws Exception {
        UUID ownerId = seedUser("auth0|cancel-owner", "OWNER");
        UUID tenantId = seedUser("auth0|cancel-tenant", "TENANT");
        UUID managerId = seedUser("auth0|cancel-manager", "REAL_ESTATE_MANAGER");

        String contractId = createContract("auth0|cancel-owner", UUID.randomUUID(), tenantId,
                managerId);

        mvc.perform(patch("/contracts/" + contractId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"El propietario retiro el inmueble del mercado.\"}")
                .with(as("auth0|cancel-manager", "REAL_ESTATE_MANAGER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledByUserId").value(managerId.toString()))
            .andExpect(jsonPath("$.cancellationReason")
                    .value("El propietario retiro el inmueble del mercado."));

        String stored = jdbc.queryForObject(
                "SELECT CAST(cancelled_by_user_id AS VARCHAR) FROM lease_contracts WHERE id = ?",
                String.class, UUID.fromString(contractId));
        org.assertj.core.api.Assertions.assertThat(stored)
                .isEqualTo(managerId.toString())
                .isNotEqualTo(ownerId.toString());
    }

    @Test
    void rejectsASignatureOutOfOrder() throws Exception {
        seedUser("auth0|order-owner", "OWNER");
        UUID tenantId = seedUser("auth0|order-tenant", "TENANT");

        String contractId = createContract("auth0|order-owner", UUID.randomUUID(), tenantId, null);

        // Firmar como propietario sin haber pasado por la firma del inquilino.
        mvc.perform(patch("/contracts/" + contractId + "/sign-as-owner")
                .with(as("auth0|order-owner", "OWNER")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATUS_FOR_OWNER_SIGN"));

        // El contrato no cambio de estado.
        mvc.perform(get("/contracts/" + contractId).with(as("auth0|order-owner", "OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }
}
