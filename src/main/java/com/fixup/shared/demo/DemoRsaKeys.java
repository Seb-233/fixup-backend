package com.fixup.shared.demo;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.stereotype.Component;

@Component
final class DemoRsaKeys {
    static final String ISSUER = "https://demo.fixup.local/";
    static final String AUDIENCE = "urn:fixup:api";
    static final RSAKey KEY = key();

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("demo-local-key").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Cannot generate demo RSA key", exception);
        }
    }

    private DemoRsaKeys() {}
}
