package com.fixup.fixers.web;

import com.fixup.fixers.api.FixerReview;
import com.fixup.fixers.api.FixerVerificationDocumentType;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.application.DocumentSubmission;
import com.fixup.fixers.application.FixerVerificationReviewView;
import com.fixup.fixers.application.FixerVerificationSummary;
import com.fixup.fixers.application.GetFixerVerification;
import com.fixup.fixers.application.GetFixerVerificationForReview;
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
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.application.UpdateFixerSpecialties;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-23: registro y validación del Fixer. Controllers stay thin and never touch JPA. */
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
    private final GetFixerVerificationForReview getVerificationForReview;
    private final SubmitFixerVerification submitVerification;
    private final FixerReview review;
    private final UpdateFixerSpecialties updateSpecialties;

    FixerVerificationController(CurrentActorProvider actors, GetFixerVerification getVerification,
            GetFixerVerificationForReview getVerificationForReview, SubmitFixerVerification submitVerification,
            FixerReview review, UpdateFixerSpecialties updateSpecialties) {
        this.actors = actors;
        this.getVerification = getVerification;
        this.getVerificationForReview = getVerificationForReview;
        this.submitVerification = submitVerification;
        this.review = review;
        this.updateSpecialties = updateSpecialties;
    }

    @GetMapping("/me/verification")
    @Operation(summary = "Read the current fixer's own verification state")
    @ApiResponse(responseCode = "200", description = "Verification state of the current fixer")
    VerificationResponse myVerification() {
        return VerificationResponse.of(getVerification.execute(actors.currentActor()));
    }

    @PostMapping("/me/specialties")
    @Operation(summary = "Update the fixer's offered specialties",
            description = "Configures the trades the fixer can attend. Requires active account and FIXER role.")
    @ApiResponse(responseCode = "200", description = "Verification state including updated specialties")
    VerificationResponse updateSpecialties(@Valid @RequestBody SpecialtiesRequest request) {
        var actor = actors.currentActor();
        updateSpecialties.execute(actor, request.specialties());
        return VerificationResponse.of(getVerification.execute(actor));
    }

    @PostMapping("/me/verification/documents")
    @Operation(summary = "File the verification documents and open the administrative review",
            description = "The body carries media IDs already uploaded and confirmed through POST /media/uploads "
                    + "with purpose FIXER_VERIFICATION. Documents may be filed one at a time; the review opens by "
                    + "itself once the mandatory set is complete. Resubmitting a type replaces its media.")
    @ApiResponse(responseCode = "200", description = "Verification state after the submission")
    VerificationResponse submit(@Valid @RequestBody DocumentsRequest request) {
        var actor = actors.currentActor();
        submitVerification.execute(actor, request.documents().stream()
                .map(document -> new DocumentSubmission(document.type(), document.mediaId())).toList(), request.consentVersion());
        return VerificationResponse.of(getVerification.execute(actor));
    }

    @GetMapping("/{fixerUserId}/verification")
    @Operation(summary = "Read a fixer's registered identity, specialties and documents for review",
            description = "Requires an active PLATFORM_ADMIN. Returns signed, time-limited read URLs for the "
                    + "submitted documents; never their storage keys.")
    @ApiResponse(responseCode = "200", description = "Verification state and documents for the requested fixer")
    ReviewResponse reviewOf(@PathVariable UUID fixerUserId) {
        return ReviewResponse.of(getVerificationForReview.execute(actors.currentActor(), fixerUserId));
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
    record SpecialtiesRequest(
            @NotEmpty @Size(min = 1, max = 6) List<@NotNull Specialty> specialties) {
        public SpecialtiesRequest {
            if (specialties != null) {
                if (specialties.contains(null)) {
                    throw new IllegalArgumentException("Specialties cannot contain null elements");
                }
                if (new HashSet<>(specialties).size() != specialties.size()) {
                    throw new IllegalArgumentException("Specialties cannot contain duplicates");
                }
            }
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DocumentsRequest(String consentVersion, @NotEmpty @Size(max = 4) @Valid List<DocumentRequest> documents) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DocumentRequest(@NotNull FixerVerificationDocumentType type, @NotNull UUID mediaId) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record RejectionRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(requiredProperties = {"status", "underReview", "submittedDocuments", "missingDocuments", "specialties"})
    record VerificationResponse(FixerVerificationStatus status, boolean underReview,
            @Schema(types = {"string", "null"}) Instant submittedAt,
            @Schema(types = {"string", "null"}) Instant decidedAt,
            @Schema(types = {"string", "null"}) String rejectionReason,
            Set<FixerVerificationDocumentType> submittedDocuments,
            Set<FixerVerificationDocumentType> missingDocuments,
            Set<Specialty> specialties) {

        static VerificationResponse of(FixerVerificationSummary summary) {
            return new VerificationResponse(summary.status(), summary.underReview(), summary.submittedAt(),
                    summary.decidedAt(), summary.rejectionReason(), summary.submittedDocuments(),
                    summary.missingDocuments(), summary.specialties());
        }
    }

    @Schema(requiredProperties = {"fixerUserId", "status", "underReview", "specialties", "documents"})
    record ReviewResponse(UUID fixerUserId, FixerVerificationStatus status, boolean underReview,
            @Schema(types = {"string", "null"}) Instant submittedAt,
            @Schema(types = {"string", "null"}) Instant decidedAt,
            @Schema(types = {"string", "null"}) UUID decidedBy,
            @Schema(types = {"string", "null"}) String rejectionReason,
            Set<Specialty> specialties, List<ReviewDocumentResponse> documents) {

        static ReviewResponse of(FixerVerificationReviewView view) {
            return new ReviewResponse(view.fixerUserId(), view.status(), view.underReview(), view.submittedAt(),
                    view.decidedAt(), view.decidedBy(), view.rejectionReason(), view.specialties(),
                    view.documents().stream().map(ReviewDocumentResponse::of).toList());
        }
    }

    @Schema(requiredProperties = {"type", "mediaId", "readUrl", "readUrlExpiresAt"})
    record ReviewDocumentResponse(FixerVerificationDocumentType type, UUID mediaId, String readUrl,
            Instant readUrlExpiresAt) {
        static ReviewDocumentResponse of(FixerVerificationReviewView.SubmittedDocumentView document) {
            return new ReviewDocumentResponse(document.type(), document.mediaId(), document.readUrl(),
                    document.readUrlExpiresAt());
        }
    }
}
