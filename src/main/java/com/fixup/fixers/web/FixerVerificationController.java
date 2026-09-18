package com.fixup.fixers.web;

import com.fixup.fixers.api.FixerReview;
import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.application.DocumentSubmission;
import com.fixup.fixers.application.FixerVerificationSummary;
import com.fixup.fixers.application.GetFixerVerification;
import com.fixup.fixers.application.SubmitFixerVerification;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

/** FR-UC-16: registro y validación del Fixer. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/fixers", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid document type, missing field or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, missing FIXER role or missing administrative privileges",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The transition does not apply to the current verification state",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class FixerVerificationController {
    private final CurrentActorProvider actors;
    private final GetFixerVerification getVerification;
    private final SubmitFixerVerification submitVerification;
    private final FixerReview review;

    FixerVerificationController(CurrentActorProvider actors, GetFixerVerification getVerification,
            SubmitFixerVerification submitVerification, FixerReview review) {
        this.actors = actors;
        this.getVerification = getVerification;
        this.submitVerification = submitVerification;
        this.review = review;
    }

    @GetMapping("/me/verification")
    @Operation(summary = "Read the current fixer's own verification state")
    @ApiResponse(responseCode = "200", description = "Verification state of the current fixer")
    VerificationResponse myVerification() {
        return VerificationResponse.of(getVerification.execute(actors.currentActor()));
    }

    @PostMapping("/me/verification/documents")
    @Operation(summary = "File the verification documents and open the administrative review",
            description = "The body carries storage keys only. No document content crosses this API. Documents may be "
                    + "filed one at a time; the review opens by itself once the mandatory set is complete. Resubmitting "
                    + "a type replaces its key.")
    @ApiResponse(responseCode = "200", description = "Verification state after the submission")
    VerificationResponse submit(@Valid @RequestBody DocumentsRequest request) {
        var actor = actors.currentActor();
        submitVerification.execute(actor, request.documents().stream()
                .map(document -> new DocumentSubmission(document.type(), document.storageKey())).toList());
        return VerificationResponse.of(getVerification.execute(actor));
    }

    @PostMapping("/{fixerUserId}/verification/approve")
    @Operation(summary = "Approve a fixer verification", description = "Requires an active PLATFORM_ADMIN.")
    @ApiResponse(responseCode = "204", description = "The fixer is verified")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@PathVariable UUID fixerUserId) {
        review.approve(actors.currentActor(), fixerUserId);
    }

    @PostMapping("/{fixerUserId}/verification/reject")
    @Operation(summary = "Reject a fixer verification", description = "Requires an active PLATFORM_ADMIN.")
    @ApiResponse(responseCode = "204", description = "The fixer is rejected and may submit again")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable UUID fixerUserId, @Valid @RequestBody RejectionRequest request) {
        review.reject(actors.currentActor(), fixerUserId, request.reason().trim());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DocumentsRequest(@NotEmpty @Size(max = 4) @Valid List<DocumentRequest> documents) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DocumentRequest(@NotNull FixerVerificationDocumentType type,
            @NotBlank @Size(max = 512) String storageKey) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record RejectionRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(requiredProperties = {"status", "underReview", "submittedDocuments", "missingDocuments"})
    record VerificationResponse(FixerVerificationStatus status, boolean underReview,
            @Schema(types = {"string", "null"}) Instant submittedAt,
            @Schema(types = {"string", "null"}) Instant decidedAt,
            @Schema(types = {"string", "null"}) String rejectionReason,
            Set<FixerVerificationDocumentType> submittedDocuments,
            Set<FixerVerificationDocumentType> missingDocuments) {

        static VerificationResponse of(FixerVerificationSummary summary) {
            return new VerificationResponse(summary.status(), summary.underReview(), summary.submittedAt(),
                    summary.decidedAt(), summary.rejectionReason(), summary.submittedDocuments(),
                    summary.missingDocuments());
        }
    }
}
