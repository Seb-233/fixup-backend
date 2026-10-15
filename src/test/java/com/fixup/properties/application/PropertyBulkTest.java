package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import com.fixup.testsupport.IntegrationDatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class PropertyBulkTest {

    @Autowired BulkImportProperties bulkImportProperties;
    @Autowired Properties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationDatabaseCleaner databaseCleaner;

    @AfterEach
    void clearDatabase() {
        jdbc.update("DELETE FROM properties");
        databaseCleaner.clean();
    }

    private CurrentActor seedOwner() {
        UUID ownerId = UUID.randomUUID();
        String auth0Id = "auth0|bulk-owner";
        jdbc.update(
            "INSERT INTO users (id, auth0_subject, email, display_name, status, created_at, updated_at) VALUES (?, ?, 'owner@b.com', 'Bulk Owner', 'ACTIVE', NOW(), NOW())",
            ownerId, auth0Id
        );
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'OWNER')", ownerId);
        return new CurrentActor(ownerId, auth0Id, Set.of(Role.OWNER), UserStatus.ACTIVE);
    }

    private String csv8Ok2Error() {
        return "name,address,city,areaM2\n" +
            "Casa 1,Calle 1,City,100\n" +
            "Casa 2,Calle 2,City,100\n" +
            "Casa 3,Calle 3,City,100\n" +
            "Casa 4,Calle 4,City,100\n" +
            "Casa 5,Calle 5,City,100\n" +
            "Casa 6,Calle 6,City,100\n" +
            "Casa 7,Calle 7,City,100\n" +
            "Casa 8,Calle 8,City,100\n" +
            ",Calle 9,City,100\n" +
            "Casa 10,Calle 10,City,-5\n";
    }

    @Test
    void validateCsvReturns2Errors() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file", "properties.csv", "text/csv", csv8Ok2Error().getBytes()
        );

        BulkValidationResult result = bulkImportProperties.validate(file);

        assertEquals(10, result.total());
        assertEquals(8, result.validCount());
        assertEquals(2, result.errors().size());

        var nameBlank = result.errors().stream()
            .filter(e -> "NAME_BLANK".equals(e.code()))
            .findFirst()
            .orElseThrow();
        assertEquals(10, nameBlank.rowNumber());
        assertEquals("name", nameBlank.field());

        var areaInvalid = result.errors().stream()
            .filter(e -> "AREA_INVALID".equals(e.code()))
            .findFirst()
            .orElseThrow();
        assertEquals(11, areaInvalid.rowNumber());
        assertEquals("areaM2", areaInvalid.field());
    }

    @Test
    void importCreates8PropertiesWithPublishedStatus() throws Exception {
        CurrentActor actor = seedOwner();
        MockMultipartFile file = new MockMultipartFile(
            "file", "properties.csv", "text/csv", csv8Ok2Error().getBytes()
        );

        BulkImportResult result = bulkImportProperties.doImport(file, actor);

        assertEquals(8, result.imported());
        assertEquals(2, result.failed());
        assertEquals(8, result.importedIds().size());
        assertEquals(2, result.errors().size());

        for (UUID id : result.importedIds()) {
            Property loaded = properties.findById(id).orElseThrow();
            assertEquals(PropertyStatus.PUBLISHED, loaded.status());
            assertEquals(actor.internalUserId(), loaded.ownerUserId());
            assertEquals(actor.internalUserId(), loaded.publishedByUserId());
            assertNotNull(loaded.publishedAt());
        }
    }
}
