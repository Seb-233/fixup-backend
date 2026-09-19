package com.fixup.payments.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.payments.api.EarningStatus;
import com.fixup.payments.api.PayoutStatus;
import com.fixup.payments.application.EarningLine;
import com.fixup.payments.application.EarningsSummary;
import com.fixup.payments.application.GetEarningsSummary;
import com.fixup.payments.application.ListOwnPayouts;
import com.fixup.payments.application.PayoutSummary;
import com.fixup.payments.application.RequestPayout;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    PaymentController(CurrentActorProvider actors, GetEarningsSummary getEarnings,
            RequestPayout requestPayout, ListOwnPayouts listPayouts) {
        this.actors = actors;
        this.getEarnings = getEarnings;
        this.requestPayout = requestPayout;
        this.listPayouts = listPayouts;
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
    PayoutResponse payout() {
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
            @Schema(types = {"string", "null"}) Instant releasedAt) {

        static EarningResponse of(EarningLine line) {
            return new EarningResponse(line.id(), line.quotationId(), line.grossAmount(),
                    line.commissionAmount(), line.netAmount(), line.commissionRateBasisPoints(),
                    line.status(), line.createdAt(), line.releasedAt());
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
