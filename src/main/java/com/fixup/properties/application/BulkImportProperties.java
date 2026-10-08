package com.fixup.properties.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.properties.api.PropertyBulkImportFinished;
import com.fixup.properties.api.PropertyPublished;
import com.fixup.properties.domain.Properties;
import com.fixup.properties.domain.Property;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class BulkImportProperties {

    private static final int CHUNK_SIZE = 100;

    private final BulkPropertyValidator validator;
    private final Properties properties;
    private final ApplicationEventPublisher events;

    public BulkImportProperties(BulkPropertyValidator validator, Properties properties, ApplicationEventPublisher events) {
        this.validator = validator;
        this.properties = properties;
        this.events = events;
    }

    public BulkValidationResult validate(MultipartFile file) throws IOException {
        List<BulkPropertyRow> rows = parseRows(file);
        List<BulkValidationError> errors = validator.validate(rows);
        int validCount = (int) rows.stream()
            .filter(row -> errors.stream().noneMatch(e -> e.rowNumber() == row.rowNumber()))
            .count();
        return new BulkValidationResult(rows.size(), validCount, errors);
    }

    public BulkImportResult doImport(MultipartFile file, CurrentActor actor) throws IOException {
        PropertyAccess.requireActiveOwner(actor);

        List<BulkPropertyRow> rows = parseRows(file);
        List<BulkValidationError> allErrors = validator.validate(rows);

        List<Integer> invalidRowNumbers = allErrors.stream().map(BulkValidationError::rowNumber).distinct().toList();
        List<BulkPropertyRow> validRows = rows.stream()
            .filter(row -> !invalidRowNumbers.contains(row.rowNumber()))
            .toList();

        List<UUID> importedIds = new ArrayList<>();
        UUID batchId = UUID.randomUUID();

        for (int i = 0; i < validRows.size(); i += CHUNK_SIZE) {
            int end = Math.min(i + CHUNK_SIZE, validRows.size());
            List<BulkPropertyRow> chunk = validRows.subList(i, end);
            List<Property> toCreate = chunk.stream()
                .map(row -> Property.create(
                    actor.internalUserId(),
                    row.name(),
                    row.address(),
                    row.city(),
                    new BigDecimal(row.areaM2Str().trim())
                ))
                .toList();
            List<Property> saved = properties.saveAll(toCreate);
            for (Property property : saved) {
                importedIds.add(property.id());
                events.publishEvent(new PropertyPublished(
                    property.id(),
                    property.ownerUserId(),
                    property.name()
                ));
            }
        }

        events.publishEvent(new PropertyBulkImportFinished(
            batchId,
            actor.internalUserId(),
            importedIds.size(),
            rows.size() - validRows.size()
        ));

        return new BulkImportResult(
            importedIds.size(),
            rows.size() - validRows.size(),
            importedIds,
            allErrors
        );
    }

    private List<BulkPropertyRow> parseRows(MultipartFile file) throws IOException {
        List<BulkPropertyRow> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT
            .builder()
            .setHeader("name", "address", "city", "areaM2")
            .setSkipHeaderRecord(true)
            .setCommentMarker('#')
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = new CSVParser(reader, format)) {
            int recordIndex = 1;
            for (CSVRecord record : parser) {
                int rowNumber = recordIndex + 1;
                String name = safeGet(record, "name");
                String address = safeGet(record, "address");
                String city = safeGet(record, "city");
                String areaM2Str = safeGet(record, "areaM2");
                rows.add(new BulkPropertyRow(rowNumber, name, address, city, areaM2Str));
                recordIndex++;
            }
        }
        return rows;
    }

    private String safeGet(CSVRecord record, String header) {
        try {
            return record.get(header);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
