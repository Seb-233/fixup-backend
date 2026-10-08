package com.fixup;

import com.fixup.shared.security.JwtValidation;
import com.nimbusds.jose.JOSEException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoJwtConfiguration {

    @Bean
    @Primary
    JwtDecoder demoDualJwtDecoder(
            @Qualifier("jwtDecoder") JwtDecoder auth0Decoder, DemoRsaKeys keys) {
        NimbusJwtDecoder demoDecoder;
        try {
            demoDecoder = NimbusJwtDecoder.withPublicKey(keys.key.toRSAPublicKey()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot build demo decoder", e);
        }
        demoDecoder.setJwtValidator(JwtValidation.validators(DemoRsaKeys.ISSUER, DemoRsaKeys.AUDIENCE));

        return new DelegatingDualJwtDecoder(auth0Decoder, demoDecoder);
    }

    /**
     * Tries the primary Auth0-backed decoder first; if validation or parsing fails with a
     * {@link JwtException}, falls back to the local demo-RSA decoder (for local smoke testing
     * against /demo/token issued JWTs without needing Auth0 M2M credentials).
     *
     * <p>The first success wins; if both fail the latest JwtException is re-thrown so the
     * standard BearerTokenAuthenticationEntryPoint still answers with 401.</p>
     */
    static final class DelegatingDualJwtDecoder implements JwtDecoder {
        private final JwtDecoder primary;
        private final JwtDecoder fallback;

        DelegatingDualJwtDecoder(JwtDecoder primary, JwtDecoder fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            JwtException lastFailure;
            try {
                Jwt jwt = primary.decode(token);
                OAuth2TokenValidatorResult result = new JwtValidatorsBridgedJwtValidatorCheck().pass(jwt);
                if (result.hasErrors()) {
                    throw new JwtException("primary validator: " + result.getErrors().iterator().next().getDescription());
                }
                return jwt;
            } catch (JwtException ex) {
                lastFailure = ex;
            }
            try {
                return fallback.decode(token);
            } catch (JwtException ignored) {
                throw lastFailure;
            }
        }
    }

    /**
     * Small bridge class so the dual decoder keeps the file self-contained. The individual
     * decoders already run their configured validators inside decode() via setJwtValidator(...);
     * this class only exists to keep the if-check above readable.
     */
    static final class JwtValidatorsBridgedJwtValidatorCheck {
        OAuth2TokenValidatorResult pass(Jwt jwt) {
            return OAuth2TokenValidatorResult.success();
        }
    }
}
