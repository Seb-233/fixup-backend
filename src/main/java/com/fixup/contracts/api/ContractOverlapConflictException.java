package com.fixup.contracts.api;

public class ContractOverlapConflictException extends RuntimeException {
    private final String code;

    public ContractOverlapConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ContractOverlapConflictException(String message) {
        this("CONTRACT_OVERLAP", message);
    }

    public String code() {
        return code;
    }
}
