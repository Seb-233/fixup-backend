package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class OpenApiCompatibility {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "options", "head", "trace");
    private static final Set<String> LEGACY_REMOVED_SCHEMAS =
            Set.of("SimulationRequest", "PaymentSimulationOutcome");
    private static final List<String> SHAPE_KEYS = List.of(
            "type", "format", "$ref", "enum", "const", "nullable", "additionalProperties",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength",
            "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "minProperties",
            "maxProperties");

    private OpenApiCompatibility() {
    }

    static List<String> findBreakingChanges(JsonNode baseline, JsonNode candidate) {
        List<String> changes = new ArrayList<>();
        compareExact(baseline.path("info"), candidate.path("info"), "info", "version", changes);
        comparePaths(baseline.path("paths"), candidate.path("paths"), changes);
        compareSchemas(baseline.path("components").path("schemas"),
                candidate.path("components").path("schemas"), changes);
        return changes;
    }

    private static void comparePaths(JsonNode baseline, JsonNode candidate, List<String> changes) {
        baseline.fields().forEachRemaining(path -> {
            JsonNode currentPath = candidate.path(path.getKey());
            if (currentPath.isMissingNode()) {
                changes.add("Removed path " + path.getKey());
                return;
            }
            path.getValue().fields().forEachRemaining(method -> {
                String methodName = method.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(methodName)) {
                    return;
                }
                String operationPath = methodName.toUpperCase() + " " + path.getKey();
                JsonNode current = currentPath.path(methodName);
                if (current.isMissingNode()) {
                    changes.add("Removed operation " + operationPath);
                    return;
                }
                compareExact(method.getValue(), current, operationPath, "operationId", changes);
                if (method.getValue().has("security")
                        && !method.getValue().path("security").equals(current.path("security"))) {
                    changes.add("Changed security requirements for " + operationPath);
                }
                compareParameters(method.getValue().path("parameters"), current.path("parameters"),
                        operationPath, changes);
                compareRequestBody(method.getValue().path("requestBody"), current.path("requestBody"),
                        operationPath, changes);
                compareResponses(method.getValue().path("responses"), current.path("responses"),
                        operationPath, changes);
            });
        });
    }

    private static void compareParameters(JsonNode baseline, JsonNode candidate, String operation,
            List<String> changes) {
        if (!baseline.isArray()) {
            return;
        }
        for (JsonNode parameter : baseline) {
            String name = parameter.path("in").asText() + ":" + parameter.path("name").asText();
            JsonNode current = findParameter(candidate, parameter.path("in").asText(),
                    parameter.path("name").asText());
            if (current.isMissingNode()) {
                changes.add("Removed parameter " + name + " from " + operation);
                continue;
            }
            compareExact(parameter, current, operation + " parameter " + name, "required", changes);
            compareSchema(parameter.path("schema"), current.path("schema"),
                    operation + " parameter " + name, changes);
        }
        if (candidate.isArray()) {
            for (JsonNode parameter : candidate) {
                if (findParameter(baseline, parameter.path("in").asText(),
                        parameter.path("name").asText()).isMissingNode()
                        && parameter.path("required").asBoolean()) {
                    changes.add("Added required parameter " + parameter.path("name").asText()
                            + " to " + operation);
                }
            }
        }
    }

    private static JsonNode findParameter(JsonNode parameters, String in, String name) {
        if (parameters.isArray()) {
            for (JsonNode parameter : parameters) {
                if (in.equals(parameter.path("in").asText())
                        && name.equals(parameter.path("name").asText())) {
                    return parameter;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static void compareRequestBody(JsonNode baseline, JsonNode candidate, String operation,
            List<String> changes) {
        if (baseline.isMissingNode()) {
            return;
        }
        if (candidate.isMissingNode()) {
            changes.add("Removed request body from " + operation);
            return;
        }
        compareExact(baseline, candidate, operation + " request body", "required", changes);
        compareContent(baseline.path("content"), candidate.path("content"),
                operation + " request body", changes);
    }

    private static void compareResponses(JsonNode baseline, JsonNode candidate, String operation,
            List<String> changes) {
        baseline.fields().forEachRemaining(response -> {
            JsonNode current = candidate.path(response.getKey());
            if (current.isMissingNode()) {
                changes.add("Removed response " + response.getKey() + " from " + operation);
                return;
            }
            compareContent(response.getValue().path("content"), current.path("content"),
                    operation + " response " + response.getKey(), changes);
        });
    }

    private static void compareContent(JsonNode baseline, JsonNode candidate, String context,
            List<String> changes) {
        baseline.fields().forEachRemaining(mediaType -> {
            JsonNode current = candidate.path(mediaType.getKey());
            if (current.isMissingNode()) {
                changes.add("Removed media type " + mediaType.getKey() + " from " + context);
                return;
            }
            compareSchema(mediaType.getValue().path("schema"), current.path("schema"),
                    context + " " + mediaType.getKey(), changes);
        });
    }

    private static void compareSchemas(JsonNode baseline, JsonNode candidate, List<String> changes) {
        baseline.fields().forEachRemaining(schema -> {
            if (LEGACY_REMOVED_SCHEMAS.contains(schema.getKey())) {
                return;
            }
            JsonNode current = candidate.path(schema.getKey());
            if (current.isMissingNode()) {
                changes.add("Removed schema " + schema.getKey());
                return;
            }
            compareSchema(schema.getValue(), current, "schema " + schema.getKey(), changes);
        });
    }

    private static void compareSchema(JsonNode baseline, JsonNode candidate, String context,
            List<String> changes) {
        if (baseline.isMissingNode() || baseline.isNull()) {
            return;
        }
        if (candidate.isMissingNode() || candidate.isNull()) {
            changes.add("Removed schema shape from " + context);
            return;
        }
        for (String key : SHAPE_KEYS) {
            compareExact(baseline, candidate, context, key, changes);
        }
        compareRequired(baseline, candidate, context, changes);
        JsonNode oldProperties = baseline.path("properties");
        JsonNode newProperties = candidate.path("properties");
        oldProperties.fields().forEachRemaining(property -> {
            JsonNode current = newProperties.path(property.getKey());
            if (current.isMissingNode()) {
                changes.add("Removed property " + context + "." + property.getKey());
            } else {
                compareSchema(property.getValue(), current, context + "." + property.getKey(), changes);
            }
        });
        compareNestedSchema(baseline, candidate, "items", context, changes);
        compareSchemaArray(baseline, candidate, "oneOf", context, changes);
        compareSchemaArray(baseline, candidate, "anyOf", context, changes);
        compareSchemaArray(baseline, candidate, "allOf", context, changes);
    }

    private static void compareRequired(JsonNode baseline, JsonNode candidate, String context,
            List<String> changes) {
        Set<String> oldRequired = stringSet(baseline.path("required"));
        Set<String> newRequired = stringSet(candidate.path("required"));
        if (!oldRequired.equals(newRequired)) {
            changes.add("Changed required properties for " + context + ": "
                    + oldRequired + " -> " + newRequired);
        }
    }

    private static Set<String> stringSet(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (values.isArray()) {
            values.forEach(value -> result.add(value.asText()));
        }
        return result;
    }

    private static void compareNestedSchema(JsonNode baseline, JsonNode candidate, String key,
            String context, List<String> changes) {
        if (baseline.has(key)) {
            compareSchema(baseline.path(key), candidate.path(key), context + "." + key, changes);
        }
    }

    private static void compareSchemaArray(JsonNode baseline, JsonNode candidate, String key,
            String context, List<String> changes) {
        JsonNode oldItems = baseline.path(key);
        JsonNode newItems = candidate.path(key);
        if (baseline.has(key) != candidate.has(key)) {
            changes.add("Changed " + key + " alternatives for " + context);
            return;
        }
        if (!oldItems.isArray()) {
            return;
        }
        if (!newItems.isArray() || oldItems.size() != newItems.size()) {
            changes.add("Changed " + key + " alternatives for " + context);
            return;
        }
        for (int index = 0; index < oldItems.size(); index++) {
            compareSchema(oldItems.get(index), newItems.get(index),
                    context + "." + key + "[" + index + "]", changes);
        }
    }

    private static void compareExact(JsonNode baseline, JsonNode candidate, String context,
            String key, List<String> changes) {
        if (baseline.has(key) != candidate.has(key)
                || (baseline.has(key) && !baseline.path(key).equals(candidate.path(key)))) {
            changes.add("Changed " + context + "." + key);
        }
    }
}
