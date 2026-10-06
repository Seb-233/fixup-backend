package com.fixup.properties.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class BulkPropertyValidator {

    public List<BulkValidationError> validate(List<BulkPropertyRow> rows) {
        List<BulkValidationError> errors = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (BulkPropertyRow row : rows) {
            validateRow(row, errors);
            checkDuplicate(row, seenKeys, errors);
        }

        return errors;
    }

    private void validateRow(BulkPropertyRow row, List<BulkValidationError> errors) {
        if (isBlank(row.name())) {
            errors.add(new BulkValidationError(row.rowNumber(), "name", "NAME_BLANK", "Name must not be blank"));
        }
        if (isBlank(row.address())) {
            errors.add(new BulkValidationError(row.rowNumber(), "address", "ADDRESS_BLANK", "Address must not be blank"));
        }
        if (isBlank(row.city())) {
            errors.add(new BulkValidationError(row.rowNumber(), "city", "CITY_BLANK", "City must not be blank"));
        }
        if (!isValidPositiveDecimal(row.areaM2Str())) {
            errors.add(new BulkValidationError(row.rowNumber(), "areaM2", "AREA_INVALID", "Area must be a positive decimal"));
        }
    }

    private void checkDuplicate(BulkPropertyRow row, Set<String> seenKeys, List<BulkValidationError> errors) {
        if (isBlank(row.address()) || isBlank(row.city())) {
            return;
        }
        String key = (row.address().trim() + "|" + row.city().trim()).toLowerCase();
        if (!seenKeys.add(key)) {
            errors.add(new BulkValidationError(
                row.rowNumber(),
                "address",
                "DUPLICATE_IN_BATCH",
                "Duplicate address+city within the same batch"
            ));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isValidPositiveDecimal(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            BigDecimal decimal = new BigDecimal(value.trim());
            return decimal.compareTo(BigDecimal.ZERO) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
