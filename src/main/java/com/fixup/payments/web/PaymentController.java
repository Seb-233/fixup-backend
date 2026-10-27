package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.application.EarningLine;
import com.fixup.payments.application.EarningsSummary;
import com.fixup.payments.application.GetEarningsSummary;
import com.fixup.payments.application.GetRepairPayment;
import com.fixup.payments.application.GetCashSettlement;
import com.fixup.payments.application.CashSettlementDetail;
import com.fixup.payments.application.InitiateDebtPayment;
import com.fixup.payments.application.ListOwnDebts;
import com.fixup.payments.application.ListOwnPayments;
import com.fixup.payments.application.OwnerDebtDetail;
import com.fixup.payments.application.ListOwnPayouts;
import com.fixup.payments.application.PaymentDetail;
import com.fixup.payments.application.PayoutSummary;
import com.fixup.payments.application.RequestPayout;
import com.fixup.payments.application.SimulateRepairPayment;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-20: monitoreo de ingresos del técnico. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "The caller cannot perform this payment operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The requested financial resource does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The requested financial transition conflicts with current state",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class PaymentController {
    private final CurrentActorProvider actors;
    private final GetEarningsSummary getEarnings;
    private final RequestPayout requestPayout;
    private final ListOwnPayouts listPayouts;
    private final GetRepairPayment getPayment;
    private final SimulateRepairPayment simulatePayment;
    private final ListOwnDebts listDebts;
    private final ListOwnPayments listPayments;
    private final InitiateDebtPayment initiateDebtPayment;
    private final GetCashSettlement getCashSettlement;

    PaymentController(CurrentActorProvider actors, GetEarningsSummary getEarnings,
            RequestPayout requestPayout, ListOwnPayouts listPayouts, GetRepairPayment getPayment,
            SimulateRepairPayment simulatePayment, ListOwnDebts listDebts,
            ListOwnPayments listPayments, InitiateDebtPayment initiateDebtPayment, GetCashSettlement getCashSettlement) {
        this.actors = actors;
        this.getEarnings = getEarnings;
        this.requestPayout = requestPayout;
        this.listPayouts = listPayouts;
        this.getPayment = getPayment;
        this.simulatePayment = simulatePayment;
        this.listDebts = listDebts;
        this.listPayments = listPayments;
        this.initiateDebtPayment = initiateDebtPayment;
        this.getCashSettlement = getCashSettlement;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    org.springframework.http.ResponseEntity<ErrorResponse> invalidIdentifierOrHeader(HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.badRequest().body(new ErrorResponse(400, "INVALID_REQUEST",
                "Payment identifiers and the required Idempotency-Key must be UUIDs", request.getRequestURI()));
    }

    @GetMapping("/cash-settlements/jobs/{jobId}")
    @Operation(summary = "Read the authoritative cash settlement for a completed job")
    @ApiResponse(responseCode = "200", description = "Audited cash settlement")
    CashSettlementDetail cashSettlement(@PathVariable UUID jobId) {
        return getCashSettlement.execute(actors.currentActor(), jobId);
    }

    @GetMapping("/me/debts")
    @Operation(summary = "List the current owner's outstanding debts")
    @ApiResponse(responseCode = "200", description = "Outstanding debts of the owner")
    List<OwnerDebtDetail> debts() {
        return listDebts.execute(actors.currentActor());
    }

    @GetMapping("/me")
    @Operation(summary = "Recover visible digital payments for the current payer",
            description = "Returns a bounded page of canonical payments owned by the caller or currently delegated "
                    + "to the caller as real-estate manager. Order is createdAt descending, then payment ID descending. "
                    + "Fixers use earnings endpoints instead.")
    @ApiResponse(responseCode = "200", description = "Bounded authorized payment page")
    PaymentPageResponse payments(@RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) com.fixup.payments.domain.PaymentStatus status,
            @RequestParam(required = false) com.fixup.payments.domain.PaymentPurpose purpose,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = listPayments.execute(actors.currentActor(), requestId, status, purpose, page, size);
        return new PaymentPageResponse(result.items(), result.page(), result.size(), result.hasMore());
    }

    @PostMapping("/debts/{debtId}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Initiate or reuse a simulated transfer for an outstanding debt")
    @ApiResponse(responseCode = "201", description = "Debt payment ready to simulate")
    PaymentDetail initiateDebt(@PathVariable UUID debtId) {
        return initiateDebtPayment.execute(actors.currentActor(), debtId);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Read a simulated service or outstanding-balance payment",
            description = "The responsible owner, current property manager, beneficiary fixer and platform administrators can audit visible payments.")
    @ApiResponse(responseCode = "200", description = "Authoritative payment state")
    PaymentDetail payment(@PathVariable UUID paymentId) {
        return getPayment.execute(actors.currentActor(), paymentId);
    }

    @PostMapping("/{paymentId}/attempts")
    @Operation(summary = "Pay the exact reference and amount through the sandbox",
            description = "Owner or current property manager. Idempotency-Key is a UUID scoped to the payment. "
                    + "Replay returns the original operation result; a different body or actor conflicts. "
                    + "The server controls the sandbox scenario. No real funds are processed.")
    @ApiResponse(responseCode = "200", description = "Immutable operation result, including on replay")
    com.fixup.payments.application.PaymentAttemptResult attempt(@PathVariable UUID paymentId,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody AttemptRequest request) {
        return simulatePayment.attempt(actors.currentActor(), paymentId,
                new com.fixup.payments.application.PaymentAttemptCommand(request.reference(), request.exactAmount(), request.instrument(), key));
    }

    @GetMapping("/{paymentId}/attempts")
    @Operation(summary = "Read immutable attempts visible to authorized payment participants")
    @ApiResponse(responseCode = "200", description = "Attempt history; unavailable legacy gateway metadata is omitted")
    List<com.fixup.payments.application.PaymentAttemptResult> history(@PathVariable UUID paymentId) {
        return getPayment.history(actors.currentActor(), paymentId);
    }

    @GetMapping("/me/earnings")
    @Operation(summary = "Read the current fixer's balance, commissions and history",
            description = "Amounts are whole Colombian pesos. The available balance is what can be "
                    + "transferred today; the held balance belongs to jobs that are not closed yet.")
    @ApiResponse(responseCode = "200", description = "Balance and history of the current fixer")
    EarningsResponse earnings() {
        return EarningsResponse.of(getEarnings.execute(actors.currentActor()));
    }

    @PostMapping("/me/payouts")
    @Operation(summary = "Simulate the transfer of the available balance",
            description = "No banking API is called. The payout reaches TRANSFERRED and its earnings "
                    + "become PAID_OUT in one transaction. Requires UUID Idempotency-Key and no body. "
                    + "Repeating a key for the same fixer returns the original payout (201), without consuming later earnings.")
    @ApiResponse(responseCode = "201", description = "The payout was simulated and transferred")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutResponse payout(HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestHeader("Idempotency-Key") UUID operationKey) throws java.io.IOException {
        // Esta operación no lleva cuerpo. Aceptar uno en silencio sería peor que rechazarlo: un
        // cliente podría mandar {"amount": ...} y creer que fue atendido cuando nunca se leyó.
        if (request.getInputStream().read() != -1) {
            throw new UnexpectedPayloadException();
        }
        return PayoutResponse.of(requestPayout.execute(actors.currentActor(), operationKey));
    }

    @GetMapping("/me/payouts")
    @Operation(summary = "List the transfer requests of the current fixer")
    @ApiResponse(responseCode = "200", description = "Transfer requests, newest first")
    List<PayoutResponse> payouts() {
        return listPayouts.execute(actors.currentActor()).stream().map(PayoutResponse::of).toList();
    }

    @Schema(requiredProperties = {"availableBalance", "heldBalance", "paidOutTotal", "totalCommission",
        "grossTotal", "history"})
    record EarningsResponse(long availableBalance, long heldBalance, long paidOutTotal,
            long totalCommission, long grossTotal, List<EarningResponse> history) {

        static EarningsResponse of(EarningsSummary summary) {
            return new EarningsResponse(summary.availableBalance(), summary.heldBalance(),
                    summary.paidOutTotal(), summary.totalCommission(), summary.grossTotal(),
                    summary.history().stream().map(EarningResponse::of).toList());
        }
    }

    @Schema(requiredProperties = {"id", "quotationId", "grossAmount", "commissionAmount", "netAmount",
        "commissionRateBasisPoints", "status", "createdAt"})
    record EarningResponse(UUID id, UUID quotationId, long grossAmount, long commissionAmount,
            long netAmount, int commissionRateBasisPoints, EarningStatus status, Instant createdAt,
            @Schema(types = {"string", "null"}) Instant releasedAt,
            @Schema(types = {"string", "null"}) Instant paidOutAt) {

        static EarningResponse of(EarningLine line) {
            return new EarningResponse(line.id(), line.quotationId(), line.grossAmount(),
                    line.commissionAmount(), line.netAmount(), line.commissionRateBasisPoints(),
                    line.status(), line.createdAt(), line.releasedAt(), line.paidOutAt());
        }
    }

    @Schema(requiredProperties = {"id", "amount", "earningCount", "status", "requestedAt", "detailStatus", "earnings"})
    record PayoutResponse(UUID id, long amount, int earningCount, PayoutStatus status,
            Instant requestedAt, @Schema(types = {"string", "null"}) Instant transferredAt,
            @Schema(types = {"string", "null"}) UUID operationKey, String detailStatus,
            List<com.fixup.payments.domain.PayoutEntry> earnings) {

        static PayoutResponse of(PayoutSummary summary) {
            return new PayoutResponse(summary.id(), summary.amount(), summary.earningCount(),
                    summary.status(), summary.requestedAt(), summary.transferredAt(), summary.operationKey(), summary.detailStatus(), summary.earnings());
        }
    }

    @Schema(requiredProperties = {"items", "page", "size", "hasMore"})
    record PaymentPageResponse(List<PaymentDetail> items, int page,
            @Schema(minimum = "1", maximum = "50") int size, boolean hasMore) { }

    @Schema(requiredProperties = {"reference", "amount", "instrument"})
    record AttemptRequest(@jakarta.validation.constraints.NotBlank String reference,
            @NotNull @Schema(type = "integer", format = "int64", minimum = "1") com.fasterxml.jackson.databind.JsonNode amount,
            @NotNull com.fixup.payments.api.PaymentMethod instrument) {
        long exactAmount() {
            if (!amount.isIntegralNumber() || !amount.canConvertToLong() || amount.longValue() <= 0) {
                throw new com.fixup.payments.api.PaymentConflictException("INVALID_PAYMENT_AMOUNT", "Amount must be positive whole pesos within int64");
            }
            return amount.longValue();
        }
    }
}
