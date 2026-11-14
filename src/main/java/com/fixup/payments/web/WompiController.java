package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.application.ListWompiTransactions;
import com.fixup.payments.application.ListWompiTransactions.WompiTransactionList;
import com.fixup.payments.application.PayWithWompi;
import com.fixup.payments.application.PayWithWompi.WompiCheckoutSession;
import com.fixup.payments.application.PayWithWompi.WompiStatus;
import com.fixup.payments.application.PayWithWompi.WompiTransactionResult;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * INT-01: pasarela Wompi. El pagador abre el checkout alojado, Wompi lo devuelve a
 * {@code /payments/{paymentId}?id=<transacción>} y el frontend pide la verificación. En paralelo,
 * Wompi avisa por el webhook firmado; los dos caminos son idempotentes.
 */
@RestController
@RequestMapping(value = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "The caller cannot pay this payment",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The payment does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The Wompi operation conflicts with the payment",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "503", description = "Wompi is not configured or did not answer",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class WompiController {
    private final CurrentActorProvider actors;
    private final PayWithWompi wompi;
    private final ListWompiTransactions transactions;

    WompiController(CurrentActorProvider actors, PayWithWompi wompi, ListWompiTransactions transactions) {
        this.actors = actors;
        this.wompi = wompi;
        this.transactions = transactions;
    }

    @GetMapping("/wompi")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Tell whether the Wompi gateway is configured and in which environment")
    @ApiResponse(responseCode = "200", description = "Gateway availability")
    WompiStatus status() {
        actors.currentActor();
        return wompi.status();
    }

    @GetMapping("/wompi/transactions")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List the merchant's Wompi transactions reconciled with FixUp payments",
            description = "Platform administrators only. Reads the Wompi API with the private key (sandbox or "
                    + "production, per the configured keys). Dates are inclusive, default to the last 30 days and span "
                    + "at most 366 days. status is APPROVED (default), DECLINED, VOIDED, ERROR, PENDING or ALL. "
                    + "Buyer data stays in Wompi.")
    @ApiResponse(responseCode = "200", description = "One page of Wompi transactions, newest first")
    WompiTransactionList transactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
            @RequestParam(required = false) @Size(max = 16) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transactions.execute(actors.currentActor(), from, until, status, page, size);
    }

    @PostMapping(value = "/{paymentId}/wompi/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Open a signed Wompi Web Checkout for the exact payment amount",
            description = "Owner or current property manager. Each call creates a new Wompi reference "
                    + "(the canonical reference plus a suffix). returnUrl must be a configured frontend origin "
                    + "and open /payments/{paymentId}; Wompi appends ?id=<transactionId>.")
    @ApiResponse(responseCode = "201", description = "Checkout URL signed with the integrity secret")
    WompiCheckoutSession checkout(@PathVariable UUID paymentId, @RequestBody CheckoutRequest request) {
        return wompi.start(actors.currentActor(), paymentId, request.returnUrl());
    }

    @PostMapping("/{paymentId}/wompi/transactions/{transactionId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Verify a Wompi transaction against the Wompi API and apply its result",
            description = "Idempotent. PENDING transactions are not recorded; call again until Wompi resolves them.")
    @ApiResponse(responseCode = "200", description = "Authoritative transaction and payment status")
    WompiTransactionResult confirm(@PathVariable UUID paymentId,
            @PathVariable @Size(max = 64) String transactionId) {
        return wompi.confirm(actors.currentActor(), paymentId, transactionId);
    }

    @PostMapping(value = "/wompi/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Wompi event webhook (transaction.updated)",
            description = "Public. Authenticity comes from the SHA-256 checksum signed with the events secret; "
                    + "the transaction is then read again from the Wompi API.")
    @ApiResponse(responseCode = "200", description = "Event accepted")
    @ApiResponse(responseCode = "401", description = "Invalid event signature")
    ResponseEntity<Void> event(@RequestBody String rawEvent,
            @RequestHeader(value = "X-Event-Checksum", required = false) String checksum) {
        return wompi.onEvent(rawEvent, checksum) ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @Schema(name = "WompiCheckoutRequest")
    record CheckoutRequest(@Size(max = 2048) String returnUrl) { }
}
