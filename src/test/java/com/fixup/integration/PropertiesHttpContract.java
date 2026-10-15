package com.fixup.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixup.properties.api.GeocodingRateLimitExceededException;
import com.fixup.properties.api.GeocodingUnavailableException;
import com.fixup.properties.application.GeocodingProvider;
import com.fixup.properties.domain.GeocodingCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class PropertiesHttpContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired com.fixup.testsupport.IntegrationDatabaseCleaner databaseCleaner;
    @MockBean GeocodingProvider geocodingProvider;

    @AfterEach
    void clearDatabase() {
        databaseCleaner.clean();
    }

    private void seedUser(UUID userId, String auth0Id) {
        seedUser(userId, auth0Id, "ACTIVE", SearchRole.OWNER);
    }

    private void seedUser(UUID userId, String auth0Id, String status, SearchRole role) {
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, ?, 'N', ?, NOW(), NOW())",
            userId, auth0Id, userId + "@example.test", status
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", userId, role.name());
    }

    @Test
    void createsAndReadsProperty() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String auth0Id = "auth0|owner";
        seedUser(ownerId, auth0Id);

        String json = """
            {
              "name": "My Property",
              "address": "123 Main St",
              "city": "Bogota",
              "areaM2": 150.50,
              "latitude": 4.711,
              "longitude": -74.072
            }
            """;

        // Create
        String response = mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("My Property"))
            .andExpect(jsonPath("$.areaM2").value(150.5))
            .andReturn().getResponse().getContentAsString();

        // Extract ID
        String idStr = mapper.readTree(response).get("id").asText();
        Boolean coordinatesPersisted = jdbc.queryForObject(
                "SELECT latitude = 4.711000 AND longitude = -74.072000 FROM properties WHERE id = ?",
                Boolean.class, UUID.fromString(idStr));
        org.assertj.core.api.Assertions.assertThat(coordinatesPersisted).isTrue();

        // Read (me)
        mvc.perform(get("/properties/me")
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(idStr));

        // Read (by ID)
        mvc.perform(get("/properties/" + idStr)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "OWNER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("My Property"));

        // Read (by PLATFORM_ADMIN)
        UUID adminId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            adminId, "auth0|admin"
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'PLATFORM_ADMIN')", adminId);

        mvc.perform(get("/properties/" + idStr)
                .with(jwt().jwt(j -> j.subject("auth0|admin").claim("roles", "PLATFORM_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("My Property"));
    }

    @Test
    void rejectsCreationByNonOwner() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String auth0Id = "auth0|tenant";
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'a@b.com', 'N', 'ACTIVE', NOW(), NOW())",
            tenantId, auth0Id
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TENANT')", tenantId);

        String json = """
            {
              "name": "T Property",
              "address": "123 Main St",
              "city": "Bogota",
              "areaM2": 150.50,
              "latitude": 4.711,
              "longitude": -74.072
            }
            """;

        mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(jwt().jwt(j -> j.subject(auth0Id).claim("roles", "TENANT"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingAndOutOfRangeCoordinatesOnCreation() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|invalid-property-location";
        seedUser(ownerId, subject);

        for (String coordinates : List.of(
                "\"longitude\": -74.072",
                "\"latitude\": 4.711",
                "\"latitude\": -90.000001, \"longitude\": -74.072",
                "\"latitude\": 90.000001, \"longitude\": -74.072",
                "\"latitude\": 4.711, \"longitude\": -180.000001",
                "\"latitude\": 4.711, \"longitude\": 180.000001",
                "\"latitude\": \"NaN\", \"longitude\": -74.072")) {
            String json = """
                    {
                      "name": "Invalid location",
                      "address": "123 Main St",
                      "city": "Bogota",
                      "areaM2": 150.50,
                      %s
                    }
                    """.formatted(coordinates);

            mvc.perform(post("/properties")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json)
                    .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isBadRequest());
        }

        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*) FROM properties", Integer.class)).isZero();
    }

    @Test
    void anotherOwnerFindsAPropertyCreatedThroughThePublishedFlowInAllAndOthers() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        String ownerASubject = "auth0|published-location-owner-a";
        String ownerBSubject = "auth0|published-location-owner-b";
        seedUser(ownerA, ownerASubject);
        seedUser(ownerB, ownerBSubject);

        String response = mvc.perform(post("/properties")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Cross-owner published property",
                          "address": "Carrera 7 # 72-41",
                          "city": "Bogotá",
                          "areaM2": 85.50,
                          "latitude": 4.711,
                          "longitude": -74.072
                        }
                        """)
                .with(jwt().jwt(j -> j.subject(ownerASubject))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String propertyId = mapper.readTree(response).get("id").asText();

        for (String scope : List.of("ALL", "OTHERS")) {
            mvc.perform(get("/properties/search")
                    .queryParam("latitude", "4.711")
                    .queryParam("longitude", "-74.072")
                    .queryParam("radiusKm", "1")
                    .queryParam("ownershipScope", scope)
                    .with(jwt().jwt(j -> j.subject(ownerBSubject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(propertyId))
                .andExpect(jsonPath("$[0].ownedByCurrentUser").value(false));
        }
    }

    @Test
    void rejectsReadByOtherOwner() throws Exception {
        UUID owner1Id = UUID.randomUUID();
        seedUser(owner1Id, "auth0|owner1");
        UUID owner2Id = UUID.randomUUID();
        seedUser(owner2Id, "auth0|owner2");

        // Insert property directly
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertPublished(jdbc, propertyId, owner1Id);

        mvc.perform(get("/properties/" + propertyId)
                .with(jwt().jwt(j -> j.subject("auth0|owner2").claim("roles", "OWNER"))))
            .andExpect(status().isNotFound());
    }

    @Test
    void searchesOnlyPublishedLocatedPropertiesInsideTheCircleAndOrdersByDistanceThenId() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geo-owner";
        seedUser(ownerId, subject);

        UUID nearestFirst = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID nearestSecond = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID nearby = UUID.fromString("00000000-0000-0000-0000-000000000003");
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, nearestSecond, ownerId, "Nearest B",
                "PUBLISHED", new BigDecimal("4.711000"), new BigDecimal("-74.072100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, nearestFirst, ownerId, "Nearest A",
                "PUBLISHED", new BigDecimal("4.711000"), new BigDecimal("-74.072100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, nearby, ownerId, "Nearby",
                "PUBLISHED", new BigDecimal("4.715000"), new BigDecimal("-74.072100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "Outside radius",
                "PUBLISHED", new BigDecimal("4.800000"), new BigDecimal("-74.072100"));
        // Inside the one-kilometre bounding box, but outside its circle.
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "Box corner",
                "PUBLISHED", new BigDecimal("4.719000"), new BigDecimal("-74.064100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "Draft",
                "DRAFT", new BigDecimal("4.711000"), new BigDecimal("-74.072100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "Archived",
                "ARCHIVED", new BigDecimal("4.711000"), new BigDecimal("-74.072100"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "No coordinates",
                "PUBLISHED", null, null);

        mvc.perform(get("/properties/search")
                .queryParam("latitude", "4.711000")
                .queryParam("longitude", "-74.072100")
                .queryParam("radiusKm", "1")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].id").value(nearestFirst.toString()))
            .andExpect(jsonPath("$[1].id").value(nearestSecond.toString()))
            .andExpect(jsonPath("$[2].id").value(nearby.toString()))
            .andExpect(jsonPath("$[0].distanceKm").value(0.0))
            .andExpect(jsonPath("$[2].distanceKm", lessThan(1.0)))
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(true))
            .andExpect(jsonPath("$[0].ownerUserId").doesNotExist())
            .andExpect(jsonPath("$[0].publishedByUserId").doesNotExist());
    }

    @Test
    void ownerCanSearchAllMineAndOthersWithOwnershipIndicator() throws Exception {
        UUID currentOwnerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();
        String subject = "auth0|scope-owner";
        seedUser(currentOwnerId, subject);
        seedUser(otherOwnerId, "auth0|scope-other");
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, mine, currentOwnerId, "Mine",
                "PUBLISHED", new BigDecimal("4.711"), new BigDecimal("-74.072"));
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, other, otherOwnerId, "Other",
                "PUBLISHED", new BigDecimal("4.712"), new BigDecimal("-74.072"));

        mvc.perform(geographicSearch(subject).queryParam("ownershipScope", "ALL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].id").value(mine.toString()))
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(true))
            .andExpect(jsonPath("$[1].id").value(other.toString()))
            .andExpect(jsonPath("$[1].ownedByCurrentUser").value(false));

        mvc.perform(geographicSearch(subject).queryParam("ownershipScope", "MINE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(mine.toString()))
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(true));

        mvc.perform(geographicSearch(subject).queryParam("ownershipScope", "OTHERS"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(other.toString()))
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(false));
    }

    @Test
    void tenantCanSearchOnlyAllAndNeverReceivesOwnership() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|search-tenant";
        seedUser(tenantId, subject, "ACTIVE", SearchRole.TENANT);
        seedUser(ownerId, "auth0|tenant-result-owner");
        UUID propertyId = UUID.randomUUID();
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, propertyId, ownerId, "Visible",
                "PUBLISHED", new BigDecimal("4.711"), new BigDecimal("-74.072"));

        mvc.perform(geographicSearch(subject))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(propertyId.toString()))
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(false));

        for (String scope : new String[]{"MINE", "OTHERS"}) {
            mvc.perform(geographicSearch(subject).queryParam("ownershipScope", scope))
                .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @EnumSource(value = SearchRole.class, names = {"FIXER", "REAL_ESTATE_MANAGER"})
    void geographicSearchRejectsDisallowedRoles(SearchRole role) throws Exception {
        UUID userId = UUID.randomUUID();
        String subject = "auth0|search-" + role.name().toLowerCase();
        seedUser(userId, subject, "ACTIVE", role);

        mvc.perform(geographicSearch(subject)).andExpect(status().isForbidden());
    }

    @Test
    void platformAdminCanSearchAllButNotOwnerSpecificScopes() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|search-admin";
        seedUser(adminId, subject, "ACTIVE", SearchRole.PLATFORM_ADMIN);
        seedUser(ownerId, "auth0|admin-result-owner");
        com.fixup.testsupport.PropertyFixtures.insertWithLocation(jdbc, UUID.randomUUID(), ownerId, "Visible",
                "PUBLISHED", new BigDecimal("4.711"), new BigDecimal("-74.072"));

        mvc.perform(geographicSearch(subject))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ownedByCurrentUser").value(false));
        mvc.perform(geographicSearch(subject).queryParam("ownershipScope", "MINE"))
            .andExpect(status().isForbidden());
    }

    @Test
    void geographicSearchReturnsEmptyWhenNothingMatches() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geo-empty";
        seedUser(ownerId, subject);

        mvc.perform(get("/properties/search")
                .queryParam("latitude", "4.711")
                .queryParam("longitude", "-74.072")
                .queryParam("radiusKm", "5")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void geographicSearchRejectsInvalidMissingAndMalformedParameters() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geo-invalid";
        seedUser(ownerId, subject);

        for (String url : new String[] {
                "/properties/search?latitude=-90.1&longitude=0&radiusKm=1",
                "/properties/search?latitude=90.1&longitude=0&radiusKm=1",
                "/properties/search?latitude=0&longitude=-180.1&radiusKm=1",
                "/properties/search?latitude=0&longitude=180.1&radiusKm=1",
                "/properties/search?latitude=0&longitude=0&radiusKm=0",
                "/properties/search?latitude=0&longitude=0&radiusKm=-1",
                "/properties/search?longitude=0&radiusKm=1",
                "/properties/search?latitude=not-a-number&longitude=0&radiusKm=1",
                "/properties/search?latitude=0&longitude=0&radiusKm=1&ownershipScope=UNKNOWN"
        }) {
            mvc.perform(get(url).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void geographicSearchRequiresAuthenticationAndAnActiveInternalActor() throws Exception {
        mvc.perform(get("/properties/search")
                .queryParam("latitude", "4.711")
                .queryParam("longitude", "-74.072")
                .queryParam("radiusKm", "5"))
            .andExpect(status().isUnauthorized());

        mvc.perform(get("/properties/search")
                .queryParam("latitude", "4.711")
                .queryParam("longitude", "-74.072")
                .queryParam("radiusKm", "5")
                .with(jwt().jwt(j -> j.subject("auth0|not-provisioned"))))
            .andExpect(status().isConflict());

        UUID inactiveId = UUID.randomUUID();
        seedUser(inactiveId, "auth0|inactive-search", "SUSPENDED", SearchRole.OWNER);
        mvc.perform(get("/properties/search")
                .queryParam("latitude", "4.711")
                .queryParam("longitude", "-74.072")
                .queryParam("radiusKm", "5")
                .with(jwt().jwt(j -> j.subject("auth0|inactive-search"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanGeocodeANormalizedAddressAndReceivesAttribution() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geocode-owner";
        seedUser(ownerId, subject);
        when(geocodingProvider.geocode("Carrera 7 # 72-41"))
                .thenReturn(java.util.List.of(new GeocodingCandidate(
                        "Carrera 7 # 72-41, Bogotá, Colombia",
                        new BigDecimal("4.657"),
                        new BigDecimal("-74.055"))));

        mvc.perform(get("/locations/geocode")
                .queryParam("query", "  Carrera   7  # 72-41 ")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Attribution", "\u00A9 OpenStreetMap contributors"))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].displayName").value("Carrera 7 # 72-41, Bogotá, Colombia"))
            .andExpect(jsonPath("$[0].latitude").value(4.657))
            .andExpect(jsonPath("$[0].longitude").value(-74.055))
            .andExpect(jsonPath("$[0].placeId").doesNotExist())
            .andExpect(jsonPath("$[0].osmType").doesNotExist());
    }

    @Test
    void ownerCanReverseGeocodeButInvalidCoordinatesAreRejected() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|reverse-geocode-owner";
        seedUser(ownerId, subject);
        var latitude = new BigDecimal("4.657");
        var longitude = new BigDecimal("-74.055");
        when(geocodingProvider.reverseGeocode(latitude, longitude))
                .thenReturn(java.util.List.of(new GeocodingCandidate(
                        "Carrera 7, Bogota", latitude, longitude)));

        mvc.perform(get("/locations/reverse-geocode")
                        .queryParam("latitude", "4.657").queryParam("longitude", "-74.055")
                        .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Attribution", "\u00A9 OpenStreetMap contributors"))
                .andExpect(jsonPath("$[0].displayName").value("Carrera 7, Bogota"))
                .andExpect(jsonPath("$[0].latitude").value(4.657));
        mvc.perform(get("/locations/reverse-geocode")
                        .queryParam("latitude", "91").queryParam("longitude", "-74")
                        .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/locations/reverse-geocode")
                        .queryParam("latitude", "4").queryParam("longitude", "-181")
                        .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tenantCanGeocodeAndReceiveAnEmptyCandidateList() throws Exception {
        UUID tenantId = UUID.randomUUID();
        String subject = "auth0|geocode-tenant";
        seedUser(tenantId, subject, "ACTIVE", SearchRole.TENANT);
        when(geocodingProvider.geocode("Dirección inexistente")).thenReturn(java.util.List.of());

        mvc.perform(get("/locations/geocode")
                .queryParam("query", "Dirección inexistente")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @ParameterizedTest
    @EnumSource(value = SearchRole.class, names = {"FIXER", "REAL_ESTATE_MANAGER"})
    void geocodingRejectsDisallowedRoles(SearchRole role) throws Exception {
        UUID userId = UUID.randomUUID();
        String subject = "auth0|geocode-" + role.name().toLowerCase();
        seedUser(userId, subject, "ACTIVE", role);

        mvc.perform(get("/locations/geocode")
                .queryParam("query", "Bogotá")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isForbidden());
        verify(geocodingProvider, never()).geocode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void geocodingRequiresAuthenticationAndAValidQuery() throws Exception {
        mvc.perform(get("/locations/geocode").queryParam("query", "Bogotá"))
            .andExpect(status().isUnauthorized());

        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geocode-invalid";
        seedUser(ownerId, subject);
        for (String query : new String[]{"   ", "a".repeat(201)}) {
            mvc.perform(get("/locations/geocode")
                    .queryParam("query", query)
                    .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/locations/geocode")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void geocodingMapsProviderFailuresWithoutAffectingPropertySearch() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String subject = "auth0|geocode-failures";
        seedUser(ownerId, subject);
        when(geocodingProvider.geocode("No disponible"))
                .thenThrow(new GeocodingUnavailableException("provider details"));
        when(geocodingProvider.geocode("Muy rápido"))
                .thenThrow(new GeocodingRateLimitExceededException());

        mvc.perform(get("/locations/geocode")
                .queryParam("query", "No disponible")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail").value("Address geocoding is temporarily unavailable"));
        mvc.perform(get("/locations/geocode")
                .queryParam("query", "Muy rápido")
                .with(jwt().jwt(j -> j.subject(subject))))
            .andExpect(status().isTooManyRequests());

        mvc.perform(geographicSearch(subject))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder geographicSearch(
            String subject) {
        return get("/properties/search")
                .queryParam("latitude", "4.711")
                .queryParam("longitude", "-74.072")
                .queryParam("radiusKm", "5")
                .with(jwt().jwt(j -> j.subject(subject)));
    }

    private enum SearchRole {
        OWNER,
        TENANT,
        FIXER,
        REAL_ESTATE_MANAGER,
        PLATFORM_ADMIN
    }
}


