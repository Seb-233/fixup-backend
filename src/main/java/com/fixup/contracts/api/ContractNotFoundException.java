package com.fixup.contracts.api;

import java.util.UUID;

public class ContractNotFoundException extends RuntimeException {
    public ContractNotFoundException(UUID contractId) {
        super("Contract not found: " + contractId);
    }
}
