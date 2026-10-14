package com.fixup.payments.infrastructure;

import com.fixup.payments.domain.PaymentGateway;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * FR-UC-22: pasarela simulada. Aprueba salvo que el escenario de prueba pida un rechazo, y responde
 * con un identificador de transacción propio del sandbox. No mueve dinero ni guarda datos de pago.
 */
@Component
class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public Result charge(Attempt attempt) {
        var transactionId = "SBX-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(Locale.ROOT);
        var scenario = attempt.scenario() == null ? "APPROVE" : attempt.scenario().trim().toUpperCase(Locale.ROOT);
        return switch (scenario) {
            case "DECLINE_FUNDS" -> new Result(false, transactionId, "Fondos insuficientes en el medio de prueba");
            case "DECLINE_BANK" -> new Result(false, transactionId, "El banco de prueba rechazó la transacción");
            default -> new Result(true, transactionId, null);
        };
    }
}
