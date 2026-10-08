package com.fixup.shared.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseConfigurationTest {
    private static final Map<String, String> REQUIRED = Map.ofEntries(
            Map.entry("DATABASE_URL", "spring.datasource.url"),
            Map.entry("DATABASE_USERNAME", "spring.datasource.username"),
            Map.entry("DATABASE_PASSWORD", "spring.datasource.password"),
            Map.entry("AUTH0_ISSUER_URI", "spring.security.oauth2.resourceserver.jwt.issuer-uri"),
            Map.entry("AUTH0_AUDIENCE", "spring.security.oauth2.resourceserver.jwt.audiences"),
            Map.entry("AUTH0_JWK_SET_URI", "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"),
            Map.entry("CORS_ALLOWED_ORIGINS", "fixup.cors.allowed-origins"),
            Map.entry("FIXUP_STORAGE_ENDPOINT", "fixup.storage.endpoint"),
            Map.entry("FIXUP_STORAGE_ACCESS_KEY", "fixup.storage.access-key"),
            Map.entry("FIXUP_STORAGE_SECRET_KEY", "fixup.storage.secret-key"));

    private StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        var environment = new StandardEnvironment();
        // Keep the test independent of workstation credentials and environment variables.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("explicit-config", variables));
        for (var source : new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD", "AUTH0_ISSUER_URI",
            "AUTH0_AUDIENCE", "AUTH0_JWK_SET_URI", "CORS_ALLOWED_ORIGINS", "FIXUP_STORAGE_ENDPOINT",
            "FIXUP_STORAGE_ACCESS_KEY", "FIXUP_STORAGE_SECRET_KEY"})
    void missingRequiredVariableFailsWithItsName(String missing) throws Exception {
        var variables = new LinkedHashMap<String, Object>();
        REQUIRED.keySet().forEach(name -> variables.put(name, "explicit-test-value"));
        variables.remove(missing);
        var environment = environment(variables);
        assertThatThrownBy(() -> environment.getRequiredProperty(REQUIRED.get(missing)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(missing);
        new ApplicationContextRunner().withUserConfiguration(SecurityConfiguration.class)
                .withInitializer(context -> context.setEnvironment(environment)).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining(missing);
                });
    }

    @Test
    void baseDisablesDevelopmentServicesAndHealthDetails() throws Exception {
        var environment = environment(Map.of());
        for (String property : new String[]{"spring.h2.console.enabled", "springdoc.api-docs.enabled",
                "springdoc.swagger-ui.enabled", "fixup.storage.auto-create-bucket"}) {
            assertThat(environment.getRequiredProperty(property, Boolean.class)).isFalse();
        }
        assertThat(environment.getRequiredProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getRequiredProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(environment.getRequiredProperty("management.endpoint.health.show-components")).isEqualTo("never");
        assertThat(environment.getProperty("spring.datasource.driver-class-name")).isNull();
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.dialect")).isNull();
    }
}
