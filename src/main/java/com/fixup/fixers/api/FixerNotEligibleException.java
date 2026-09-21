package com.fixup.fixers.api;

public class FixerNotEligibleException extends RuntimeException {
    public FixerNotEligibleException() {
        super("A verified, active fixer is required");
    }
}
