package com.fixup.identityaccess.domain;

public class IdentityProblem extends RuntimeException {
    public enum Reason { ACCESS_DENIED, USER_NOT_PROVISIONED, IDENTITY_CONFLICT, INVALID_PROFILE }
    private final Reason reason;

    public IdentityProblem(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
