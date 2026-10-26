package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentGateway;
import com.fixup.payments.domain.PaymentStatus;
import com.fixup.payments.api.SandboxScenario;
import com.fixup.payments.api.PaymentConflictException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FR-UC-22: pasarela simulada. Solo aprueba el escenario explicito APPROVE, y responde
 * con un identificador de transacción propio del sandbox. No mueve dinero ni guarda datos de pago.
 */
@Component
class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public Result charge(Attempt attempt) {
        var transactionId = "SBX-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        if (attempt.method() == null) {
            throw new PaymentConflictException("INVALID_INSTRUMENT", "A sandbox instrument is required");
        }
        final SandboxScenario scenario;
        try {
            scenario = SandboxScenario.valueOf(attempt.scenario());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new PaymentConflictException("INVALID_SANDBOX_SCENARIO", "The server sandbox scenario is invalid");
        }
        return switch (scenario) {
            case DECLINE_FUNDS -> new Result(PaymentStatus.DECLINED, transactionId, "Fondos insuficientes en el medio de prueba");
            case DECLINE_BANK -> new Result(PaymentStatus.DECLINED, transactionId, "El banco de prueba rechazó la transacción");
            case PENDING -> new Result(PaymentStatus.PENDING, transactionId, null);
            case ERROR -> new Result(PaymentStatus.ERROR, transactionId, "Error simulado del proveedor");
            case APPROVE -> new Result(PaymentStatus.APPROVED, transactionId, null);
        };
    }
}
