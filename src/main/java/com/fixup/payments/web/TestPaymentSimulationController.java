package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.api.PaymentSimulationOutcome;
import com.fixup.payments.application.PaymentDetail;
import com.fixup.payments.application.SimulateRepairPayment;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Compatibility harness only: production clients cannot select a provider outcome. */
@RestController
@Profile("test")
@Hidden
class TestPaymentSimulationController {
    private final CurrentActorProvider actors;
    private final SimulateRepairPayment payments;
    TestPaymentSimulationController(CurrentActorProvider actors, SimulateRepairPayment payments) {
        this.actors = actors;
        this.payments = payments;
    }
    @PostMapping("/payments/{paymentId}/simulate")
    PaymentDetail simulate(@PathVariable UUID paymentId, @Valid @RequestBody SimulationRequest request) {
        return payments.execute(actors.currentActor(), paymentId, request.outcome());
    }
    record SimulationRequest(@NotNull PaymentSimulationOutcome outcome) { }
}
