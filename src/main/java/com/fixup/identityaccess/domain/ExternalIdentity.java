package com.fixup.identityaccess.domain;

public record ExternalIdentity(String subject, String email, String displayName) {
    public ExternalIdentity {
        if (subject == null || subject.isBlank() || subject.length() > 255
                || (email != null && email.length() > 320)
                || (displayName != null && displayName.length() > 200)) {
            throw new IdentityProblem(IdentityProblem.Reason.INVALID_PROFILE);
        }
    }
}
