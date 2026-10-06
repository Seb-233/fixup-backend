package com.fixup.shared.demo;

import com.fixup.identityaccess.api.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
@Profile("demo")
class DemoTokenEndpoint {

    @GetMapping("/token")
    ResponseEntity<DemoTokenResponse> token(
            @RequestParam Optional<String> sub,
            @RequestParam Optional<String> email,
            @RequestParam Optional<String> name,
            @RequestParam(defaultValue = "OWNER") String role,
            @RequestParam(defaultValue = "3600") long ttlSeconds) throws JOSEException {
        Set<Role> roles = parseRoles(role);
        boolean isSuper = "SUPER_USER".equalsIgnoreCase(role.trim());
        String subject = sub.orElseGet(() -> isSuper ? "auth0|demo-superuser" : "auth0|demo-" + role.toLowerCase());
        String emailAddress = email.orElseGet(() -> isSuper ? "demo-superuser@fixup.app" : "demo-" + role.toLowerCase() + "@fixup.app");
        String displayName = name.orElseGet(() -> "Demo " + String.join("+", roles.stream().map(Enum::name).toList()));
        String serialized = sign(subject, emailAddress, displayName, ttlSeconds);
        return ResponseEntity.ok(new DemoTokenResponse(serialized, subject, emailAddress, displayName,
                roles.stream().map(Enum::name).toList(),
                DemoRsaKeys.ISSUER, DemoRsaKeys.AUDIENCE, ttlSeconds,
                "Authorization: Bearer " + serialized));
    }

    @GetMapping("/token/{role}")
    ResponseEntity<DemoTokenResponse> tokenForRole(@PathVariable String role) throws JOSEException {
        return token(Optional.empty(), Optional.empty(), Optional.empty(), role, 3600);
    }

    record DemoTokenResponse(String accessToken, String subject, String email, String displayName,
                              List<String> roles, String issuer, String audience,
                              long ttlSeconds, String usageHint) {}

    private Set<Role> parseRoles(String rolesCsv) {
        String normalized = Optional.ofNullable(rolesCsv).map(String::trim).filter(s -> !s.isEmpty()).orElse("OWNER");
        if ("SUPER_USER".equalsIgnoreCase(normalized)) {
            return Set.of(Role.values());
        }
        return Arrays.stream(normalized.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .map(token -> switch (token) {
                    case "SUPER_USER", "ALL_ROLES" -> null; // handled above via whole-string; just skip here
                    case "ADMIN", "ADMINISTRATOR", "PLATFORM_ADMIN" -> Role.PLATFORM_ADMIN;
                    case "MANAGER", "REAL_ESTATE_MANAGER", "ADMIN_INMOB" -> Role.REAL_ESTATE_MANAGER;
                    case "OWNER", "PROPIETARIO" -> Role.OWNER;
                    case "TENANT", "INQUILINO", "RENTA" -> Role.TENANT;
                    case "FIXER", "TECNICO", "TÉCNICO" -> Role.FIXER;
                    default -> Role.OWNER;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String sign(String subject, String email, String displayName, long ttlSeconds) throws JOSEException {
        Instant now = Instant.now().minusSeconds(2);
        Instant exp = now.plusSeconds(Math.max(60L, ttlSeconds));
        var claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(DemoRsaKeys.ISSUER)
                .audience(List.of(DemoRsaKeys.AUDIENCE))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim("email", email)
                .claim("name", displayName)
                .claim("preferred_username", displayName.toLowerCase().replace(' ', '-'))
                .build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(DemoRsaKeys.KEY.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(DemoRsaKeys.KEY));
        return token.serialize();
    }
}
