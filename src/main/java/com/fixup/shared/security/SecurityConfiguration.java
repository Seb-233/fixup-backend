package com.fixup.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.filter.CorsFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    static BeanFactoryPostProcessor requiredBackendConfiguration(Environment environment) {
        // Resolve required placeholders before opening connections or creating clients.
        return beanFactory -> {
            for (String property : List.of("spring.datasource.url", "spring.datasource.username",
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "spring.security.oauth2.resourceserver.jwt.audiences",
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                    "fixup.cors.allowed-origins", "fixup.storage.endpoint",
                    "fixup.storage.access-key", "fixup.storage.secret-key")) {
                Assert.hasText(environment.getRequiredProperty(property),
                        "Required backend property must not be blank: " + property);
            }
            String password = environment.getRequiredProperty("spring.datasource.password");
            if (!(environment.matchesProfiles("test", "demo")
                    && environment.getRequiredProperty("spring.datasource.url").startsWith("jdbc:h2:"))) {
                Assert.hasText(password, "Required backend property must not be blank: spring.datasource.password");
            }
        };
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        // Keys are fetched lazily: public health does not require contacting Auth0 at startup.
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidation.validators(issuer, audience));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler deniedHandler,
            CorsConfigurationSource corsConfigurationSource, ObjectMapper mapper,
            Environment environment) throws Exception {
        var converter = new JwtAuthenticationConverter();
        // Auth0 authenticates identity; neither JWT roles nor scopes grant internal privileges.
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of());
        var corsFilter = new CorsFilter(corsConfigurationSource);
        corsFilter.setCorsProcessor(new JsonCorsProcessor(mapper));
        return http
                // Bearer-only API: no session cookies are used for authentication.
                .csrf(csrf -> csrf.disable())
                .addFilter(corsFilter)
                .logout(logout -> logout.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/actuator/health").permitAll();
                    if (environment.matchesProfiles("demo")) {
                        requests.requestMatchers("/demo/**").permitAll();
                    }
                    if (environment.matchesProfiles("dev", "demo")) {
                        requests.requestMatchers("/swagger-ui.html", "/swagger-ui", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll();
                    }
                    requests.requestMatchers("/auth/**").authenticated()
                            .requestMatchers("/fixers/**").authenticated()
                            .requestMatchers("/media/**").authenticated()
                            .requestMatchers("/analytics/**").authenticated()
                            .requestMatchers("/requests/**").authenticated()
                            .requestMatchers("/quotations/**").authenticated()
                            .requestMatchers("/jobs/**").authenticated()
                            .requestMatchers("/payments/**").authenticated()
                            .requestMatchers("/properties/**").authenticated()
                            .requestMatchers("/contracts/**").authenticated()
                            .requestMatchers("/notifications/**").authenticated()
                            .requestMatchers("/administration/**").authenticated()
                            .requestMatchers("/users/**").authenticated()
                            .requestMatchers("/portfolio/**").authenticated()
                            .requestMatchers("/earnings/**").authenticated()
                            .requestMatchers("/chat/**", "/messages/**").authenticated()
                            .anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${fixup.cors.allowed-origins}") String configuredOrigins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(configuredOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}

