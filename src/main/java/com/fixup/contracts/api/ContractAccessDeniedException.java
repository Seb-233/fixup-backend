package com.fixup.contracts.api;

public class ContractAccessDeniedException extends RuntimeException {
    public ContractAccessDeniedException(String message) {
        super(message);
    }
}
