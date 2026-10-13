package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.api.ChargeStatus;
import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PaymentMethod;
import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.application.EarningLine;
import com.fixup.payments.application.EarningsSummary;
import com.fixup.payments.application.GetEarningsSummary;
import com.fixup.payments.application.ListOwnPayouts;
import com.fixup.payments.application.ListOwnerCharges;
import com.fixup.payments.application.PayCharge;
import com.fixup.payments.application.PayoutSummary;
import com.fixup.payments.application.RequestPayout;
import com.fixup.payments.domain.OwnerCharge;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** FR-UC-20: monitoreo de ingresos del técnico. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or missing FIXER role",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The account has no payment record",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "There is no available balance to transfer",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class PaymentController {
    private final CurrentActorProvider actors;
    private final GetEarningsSummary getEarnings;
    private final RequestPayout requestPayout;
    private final ListOwnPayouts listPayouts;
    private final ListOwnerCharges listCharges;
    private final PayCharge payCharge;

    PaymentController(CurrentActorProvider actors, GetEarningsSummary getEarnings,
            RequestPayout requestPayout, ListOwnPayouts listPayouts, ListOwnerCharges listCharges,
            PayCharge payCharge) {
        this.actors = actors;
        this.getEarnings = getEarnings;
        this.requestPayout = requestPayout;
        this.listPayouts = listPayouts;
        this.listCharges = listCharges;
        this.payCharge = payCharge;
    }

    @GetMapping("/charges/me")
    @Operation(summary = "Charges the current owner or manager must pay or already paid (FR-UC-05)")
    List<ChargeResponse> myCharges() {
        return listCharges.mine(actors.currentActor()).stream().map(ChargeResponse::of).toList();
    }

    @GetMapping("/charges/for-request/{requestId}")
    @Operation(summary = "The charge of a repair request, for the payer, the fixer or a platform admin")
    ChargeResponse chargeForRequest(@PathVariable UUID requestId) {
        return ChargeResponse.of(listCharges.forRequest(actors.currentActor(), requestId));
    }

    @PostMapping(value = "/charges/{chargeId}/pay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pay a charge in the sandbox gateway (FR-UC-22)",
            description = "Academic scope: no card, account or phone data is sent or stored. The gateway checks "
                    + "that the payer, the reference and the amount match the charge. scenario=DECLINE_FUNDS or "
                    + "DECLINE_BANK simulates a rejection.")
    ChargeResponse pay(@PathVariable UUID chargeId, @Valid @RequestBody PayRequest body) {
        return ChargeResponse.of(payCharge.execute(actors.currentActor(), chargeId, body.reference(), body.amount(),
                body.method(), body.scenario()));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record PayRequest(@NotBlank @Size(max = 32) String reference, @NotNull @Positive Long amount,
            @NotNull PaymentMethod method, @Size(max = 20) String scenario) {
    }

    @Schema(requiredProperties = {"id", "quotationId", "requestId", "amount", "reference", "status", "attempts",
        "createdAt"})
    record ChargeResponse(UUID id, UUID quotationId, UUID requestId, @Schema(types = {"string", "null"}) UUID propertyId,
            UUID fixerUserId, @Schema(types = {"string", "null"}) String specialty,
            @Schema(types = {"string", "null"}) String concept, long amount, String reference, ChargeStatus status,
            @Schema(types = {"string", "null"}) PaymentMethod method,
            @Schema(types = {"string", "null"}) String gatewayTransactionId,
            @Schema(types = {"string", "null"}) String failureReason, int attempts, Instant createdAt,
            @Schema(types = {"string", "null"}) Instant approvedAt) {

        static ChargeResponse of(OwnerCharge charge) {
            return new ChargeResponse(charge.id(), charge.quotationId(), charge.requestId(), charge.propertyId(),
                    charge.fixerUserId(), charge.specialty(), charge.concept(), charge.amount(), charge.reference(),
                    charge.status(), charge.method(), charge.gatewayTransactionId(), charge.failureReason(),
                    charge.attempts(), charge.createdAt(), charge.approvedAt());
        }
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
    @Operation(summary = "Request the transfer of the available balance",
            description = "Academic scope: the request is recorded, no banking API is called. "
                    + "Reading the balance and consuming it happen in the same transaction.")
    @ApiResponse(responseCode = "201", description = "The transfer request was recorded")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutResponse payout(HttpServletRequest request) {
        // Esta operación no lleva cuerpo. Aceptar uno en silencio sería peor que rechazarlo: un
        // cliente podría mandar {"amount": ...} y creer que fue atendido cuando nunca se leyó.
        if (request.getContentLengthLong() > 0) {
            throw new UnexpectedPayloadException();
        }
        return PayoutResponse.of(requestPayout.execute(actors.currentActor()));
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
            @Schema(types = {"string", "null"}) Instant paidOutAt,
            @Schema(types = {"string", "null"}) Instant fundedAt,
            @Schema(types = {"string", "null"}) Instant jobCompletedAt) {

        static EarningResponse of(EarningLine line) {
            return new EarningResponse(line.id(), line.quotationId(), line.grossAmount(),
                    line.commissionAmount(), line.netAmount(), line.commissionRateBasisPoints(),
                    line.status(), line.createdAt(), line.releasedAt(), line.paidOutAt(), line.fundedAt(),
                    line.jobCompletedAt());
        }
    }

    @Schema(requiredProperties = {"id", "amount", "earningCount", "status", "requestedAt"})
    record PayoutResponse(UUID id, long amount, int earningCount, PayoutStatus status,
            Instant requestedAt) {

        static PayoutResponse of(PayoutSummary summary) {
            return new PayoutResponse(summary.id(), summary.amount(), summary.earningCount(),
                    summary.status(), summary.requestedAt());
        }
    }
}
