package com.fixup.integration;

import com.fixup.shared.security.JwtValidation;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Ephemeral keys are generated in memory; no Auth0 credentials or private key fixtures. */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {
    static final String ISSUER = "https://issuer.example.test/";
    static final String AUDIENCE = "https://api.example.test";
    static final RSAKey KEY = key();
    static final RSAKey OTHER_KEY = key();

    static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot generate ephemeral test key", exception);
        }
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() throws JOSEException {
        var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidation.validators(ISSUER, AUDIENCE));
        return decoder;
    }

    static String token(String subject) throws JOSEException {
        return token(subject, ISSUER, AUDIENCE, Instant.now().plusSeconds(300),
                Instant.now().minusSeconds(5), KEY);
    }

    static String token(String subject, String issuer, String audience, Instant expires, Instant notBefore,
            RSAKey key) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .notBeforeTime(Date.from(notBefore))
                .claim("email", "synthetic@example.test").claim("name", "Synthetic account");
        if (expires != null) {
            claims.expirationTime(Date.from(expires));
        }
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
