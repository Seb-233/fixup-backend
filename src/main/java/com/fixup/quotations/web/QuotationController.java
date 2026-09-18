package com.fixup.quotations.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.quotations.api.QuotationStatus;
import com.fixup.quotations.application.AcceptQuotation;
import com.fixup.quotations.application.ListOwnQuotations;
import com.fixup.quotations.application.ListQuotationsForRequest;
import com.fixup.quotations.application.NewQuotation;
import com.fixup.quotations.application.QuotationSummary;
import com.fixup.quotations.application.SubmitQuotation;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-18: responder solicitudes y cotizar. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/quotations", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid amount, invalid estimate or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, unverified fixer or quotation not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The quotation or the repair request does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The transition does not apply to the current state of the quotation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class QuotationController {
    private final CurrentActorProvider actors;
    private final SubmitQuotation submitQuotation;
    private final ListOwnQuotations listOwn;
    private final ListQuotationsForRequest listForRequest;
    private final AcceptQuotation acceptQuotation;

    QuotationController(CurrentActorProvider actors, SubmitQuotation submitQuotation,
            ListOwnQuotations listOwn, ListQuotationsForRequest listForRequest,
            AcceptQuotation acceptQuotation) {
        this.actors = actors;
        this.submitQuotation = submitQuotation;
        this.listOwn = listOwn;
        this.listForRequest = listForRequest;
        this.acceptQuotation = acceptQuotation;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Send a quotation for a repair request",
            description = "Requires an active fixer whose verification is already approved. "
                    + "The amount is expressed in whole Colombian pesos.")
    @ApiResponse(responseCode = "201", description = "The quotation reached the owner of the request")
    @ResponseStatus(HttpStatus.CREATED)
    QuotationResponse submit(@Valid @RequestBody QuotationRequest body) {
        return QuotationResponse.of(submitQuotation.execute(actors.currentActor(),
                new NewQuotation(body.requestId(), body.amount(), body.estimatedDays(), body.message())));
    }

    @GetMapping("/me")
    @Operation(summary = "List the quotations sent by the current fixer")
    @ApiResponse(responseCode = "200", description = "Quotations of the current fixer, newest first")
    List<QuotationResponse> mine() {
        return listOwn.execute(actors.currentActor()).stream().map(QuotationResponse::of).toList();
    }

    @GetMapping("/for-request/{requestId}")
    @Operation(summary = "Compare the quotations received for a repair request",
            description = "Only the owner of the request reads them, cheapest offer first.")
    @ApiResponse(responseCode = "200", description = "Quotations received, cheapest first")
    List<QuotationResponse> forRequest(@PathVariable UUID requestId) {
        return listForRequest.execute(actors.currentActor(), requestId).stream()
                .map(QuotationResponse::of).toList();
    }

    @PostMapping("/{quotationId}/accept")
    @Operation(summary = "Accept a quotation",
            description = "Closes the request against the chosen fixer and rejects the remaining offers "
                    + "in the same transaction.")
    @ApiResponse(responseCode = "200", description = "The accepted quotation")
    QuotationResponse accept(@PathVariable UUID quotationId) {
        return QuotationResponse.of(acceptQuotation.execute(actors.currentActor(), quotationId));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record QuotationRequest(@NotNull UUID requestId, @NotNull @Min(1) Long amount,
            @NotNull @Min(1) @Max(365) Integer estimatedDays, @Size(max = 1000) String message) {
    }

    @Schema(requiredProperties = {"id", "requestId", "fixerUserId", "amount", "estimatedDays", "status",
        "createdAt"})
    record QuotationResponse(UUID id, UUID requestId, UUID fixerUserId, long amount, int estimatedDays,
            @Schema(types = {"string", "null"}) String message, QuotationStatus status, Instant createdAt) {

        static QuotationResponse of(QuotationSummary summary) {
            return new QuotationResponse(summary.id(), summary.requestId(), summary.fixerUserId(),
                    summary.amount(), summary.estimatedDays(), summary.message(), summary.status(),
                    summary.createdAt());
        }
    }
}
