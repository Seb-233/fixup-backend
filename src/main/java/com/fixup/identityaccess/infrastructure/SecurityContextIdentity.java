package com.fixup.identityaccess.infrastructure;

import com.fixup.identityaccess.application.ExternalIdentityProvider;
import com.fixup.identityaccess.domain.ExternalIdentity;
import com.fixup.identityaccess.domain.IdentityProblem;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class SecurityContextIdentity implements ExternalIdentityProvider {
    private Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
                || token.getToken().getSubject() == null || token.getToken().getSubject().isBlank()) {
            throw new InsufficientAuthenticationException("A validated bearer token is required");
        }
        return token.getToken();
    }

    @Override
    public String currentSubject() {
        return jwt().getSubject();
    }

    @Override
    public ExternalIdentity currentIdentity() {
        var jwt = jwt();
        return new ExternalIdentity(jwt.getSubject(), optionalString(jwt, "email"), optionalString(jwt, "name"));
    }

    private String optionalString(Jwt jwt, String claim) {
        Object value = jwt.getClaims().get(claim);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IdentityProblem(IdentityProblem.Reason.INVALID_PROFILE);
        }
        return text;
    }
}
