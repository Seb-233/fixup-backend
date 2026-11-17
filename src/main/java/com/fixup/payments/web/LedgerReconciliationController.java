package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.application.LedgerReconciliation;
import com.fixup.payments.application.ReconcileLedger;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safety: el administrador ve en vivo si el libro de pagos cuadra. Controller delgado. */
@RestController
@RequestMapping(value = "/administration/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Missing PLATFORM_ADMIN role or inactive account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class LedgerReconciliationController {
    private final CurrentActorProvider actors;
    private final ReconcileLedger reconcile;

    LedgerReconciliationController(CurrentActorProvider actors, ReconcileLedger reconcile) {
        this.actors = actors;
        this.reconcile = reconcile;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Run the payment ledger reconciliation now and return its result")
    @ApiResponse(responseCode = "200", description = "The ledger was read: totals in whole COP, rows scanned per "
            + "table, every conservation equation and every broken invariant with a sample of row ids")
    @ApiResponse(responseCode = "503", description = "No result: LEDGER_RECONCILIATION_DISABLED (turned off until "
            + "the ledger reader is provisioned; no Retry-After), LEDGER_UNREADABLE (the ledger could not be read) "
            + "or LEDGER_RECONCILIATION_BUSY (another instance is reconciling). Never means 'no findings'",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    LedgerReconciliation reconciliation() {
        return reconcile.runNow(actors.currentActor());
    }
}
