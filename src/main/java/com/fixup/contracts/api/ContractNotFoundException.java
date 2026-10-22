package com.fixup.contracts.api;

import java.util.UUID;

public class ContractNotFoundException extends RuntimeException {
    public ContractNotFoundException(UUID contractId) {
        super("Contract not found: " + contractId);
    }

    private ContractNotFoundException(String message) {
        super(message);
    }

    /** Respuesta de la búsqueda de arrendatario cuando el correo no es de un arrendatario activo. */
    public static ContractNotFoundException tenantNotFound() {
        return new ContractNotFoundException("No active tenant uses that email");
    }
}
