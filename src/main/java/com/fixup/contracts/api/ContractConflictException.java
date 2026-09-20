package com.fixup.contracts.api;

public class ContractConflictException extends RuntimeException {
    private final String code;

    public ContractConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
