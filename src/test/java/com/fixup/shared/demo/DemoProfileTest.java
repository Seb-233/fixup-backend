package com.fixup.shared.demo;

import com.fixup.identityaccess.application.RoleAssignments;
import com.fixup.identityaccess.application.UserRegistration;
import com.fixup.notifications.domain.Notificaciones;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DemoProfileTest {
    private final JwtDecoder auth0 = mock(JwtDecoder.class);
    private final UserRegistration registration = mock(UserRegistration.class);
    private final RoleAssignments assignments = mock(RoleAssignments.class);
    private final Notificaciones notifications = mock(Notificaciones.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DemoScan.class)
            .withBean("jwtDecoder", JwtDecoder.class, () -> auth0)
            .withBean(UserRegistration.class, () -> registration)
            .withBean(RoleAssignments.class, () -> assignments)
            .withBean(Notificaciones.class, () -> notifications)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = DemoRsaKeys.class)
    static class DemoScan {}

    @ParameterizedTest
    @ValueSource(strings = {"", "dev", "test", "production"})
    void noLocalIssuerKeysOrBootstrapOutsideDemo(String profile) {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(
                profile.isEmpty() ? new String[0] : new String[]{profile})).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class)
                    .doesNotHaveBean(DemoRsaKeys.class)
                    .doesNotHaveBean(DemoTokenEndpoint.class)
                    .doesNotHaveBean(DemoJwtConfiguration.class)
                    .doesNotHaveBean(DemoUsersBootstrapRunner.class);
            assertThat(context.getBean(JwtDecoder.class)).isSameAs(auth0);
            verifyNoInteractions(auth0, registration, assignments, notifications);
        });
    }

    @Test
    void demoLoadsAllComponentsAndReusesAuth0Decoder() {
        runner.withPropertyValues("spring.profiles.active=demo").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DemoRsaKeys.class)
                    .hasSingleBean(DemoTokenEndpoint.class).hasSingleBean(DemoUsersBootstrapRunner.class);
            var decoder = context.getBean(JwtDecoder.class);
            var identity = Jwt.withTokenValue("auth0-token").header("alg", "RS256")
                    .subject("auth0|real-user").build();
            when(auth0.decode("auth0-token")).thenReturn(identity);
            assertThat(decoder.decode("auth0-token")).isSameAs(identity);

            var endpoint = context.getBean(DemoTokenEndpoint.class);
            var response = endpoint.token(Optional.empty(), Optional.empty(), Optional.empty(), "OWNER", 3600)
                    .getBody();
            assertThat(response).isNotNull();
            when(auth0.decode(response.accessToken())).thenThrow(new JwtException("Not an Auth0 token"));
            var decoded = decoder.decode(response.accessToken());
            assertThat(decoded.getIssuer().toString()).isEqualTo(DemoRsaKeys.ISSUER);
            assertThat(decoded.getAudience()).containsExactly(DemoRsaKeys.AUDIENCE);
            assertThat(decoded.getSubject()).isEqualTo("auth0|demo-owner");
            assertThat(decoded.getClaims()).doesNotContainKey("roles");

            when(auth0.decode("invalid")).thenThrow(new JwtException("Invalid token"));
            assertThatThrownBy(() -> decoder.decode("invalid")).isInstanceOf(JwtException.class);
        });
    }
}
