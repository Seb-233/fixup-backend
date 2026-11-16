package com.fixup.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsAnOptionalResponseField() throws Exception {
        JsonNode baseline = contract();
        ObjectNode candidate = (ObjectNode) contract();
        ((ObjectNode) candidate.at("/components/schemas/RequestResponse/properties"))
                .put("summary", "string");

        assertThat(OpenApiCompatibility.findBreakingChanges(baseline, candidate)).isEmpty();
    }

    @Test
    void rejectsRemovingAVersionedRoute() throws Exception {
        JsonNode baseline = contract();
        ObjectNode candidate = (ObjectNode) contract();
        ((ObjectNode) candidate.path("paths")).remove("/requests");

        assertThat(OpenApiCompatibility.findBreakingChanges(baseline, candidate))
                .contains("Removed path /requests");
    }

    @Test
    void rejectsChangingAResponsePropertyType() throws Exception {
        JsonNode baseline = contract();
        ObjectNode candidate = (ObjectNode) contract();
        ((ObjectNode) candidate.at("/components/schemas/RequestResponse/properties/id"))
                .put("type", "integer");

        assertThat(OpenApiCompatibility.findBreakingChanges(baseline, candidate))
                .contains("Changed schema RequestResponse.id.type");
    }

    @Test
    void rejectsAddingARequiredRequestProperty() throws Exception {
        JsonNode baseline = contract();
        ObjectNode candidate = (ObjectNode) contract();
        ObjectNode request = (ObjectNode) candidate.at("/components/schemas/CreateRequest");
        ((ObjectNode) request.path("properties")).putObject("email").put("type", "string");
        ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("required")).add("email");

        assertThat(OpenApiCompatibility.findBreakingChanges(baseline, candidate))
                .anySatisfy(change -> assertThat(change).contains("Changed required properties"));
    }

    @Test
    void rejectsChangingThePublishedContractVersion() throws Exception {
        JsonNode baseline = contract();
        ObjectNode candidate = (ObjectNode) contract();
        ((ObjectNode) candidate.path("info")).put("version", "v2");

        assertThat(OpenApiCompatibility.findBreakingChanges(baseline, candidate))
                .contains("Changed info.version");
    }

    private JsonNode contract() throws Exception {
        return mapper.readTree("""
                {
                  "info": { "version": "v1" },
                  "paths": {
                    "/requests": {
                      "post": {
                        "operationId": "createRequest",
                        "security": [{ "bearerAuth": [] }],
                        "parameters": [{
                          "name": "limit",
                          "in": "query",
                          "required": false,
                          "schema": { "type": "integer", "format": "int32" }
                        }],
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/json": {
                              "schema": { "$ref": "#/components/schemas/CreateRequest" }
                            }
                          }
                        },
                        "responses": {
                          "201": {
                            "description": "Created",
                            "content": {
                              "application/json": {
                                "schema": { "$ref": "#/components/schemas/RequestResponse" }
                              }
                            }
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "CreateRequest": {
                        "type": "object",
                        "required": ["name"],
                        "properties": { "name": { "type": "string" } }
                      },
                      "RequestResponse": {
                        "type": "object",
                        "required": ["id"],
                        "properties": { "id": { "type": "string", "format": "uuid" } }
                      }
                    }
                  }
                }
                """);
    }
}
