package com.fixup.jobs.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.application.AddJobEvidence;
import com.fixup.jobs.application.CompleteJob;
import com.fixup.jobs.application.GetJobDetail;
import com.fixup.jobs.application.JobSummary;
import com.fixup.jobs.application.ListOwnJobs;
import com.fixup.jobs.application.ProgressJob;
import com.fixup.jobs.domain.JobEvidence;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-19 y FR-UC-20: trabajos del técnico. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid identifier or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, unverified fixer or job assigned to someone else",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The job does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The transition does not apply to the current state of the job",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class JobController {
    private final CurrentActorProvider actors;
    private final ListOwnJobs listOwn;
    private final CompleteJob completeJob;
    private final ProgressJob progressJob;
    private final AddJobEvidence addEvidence;
    private final GetJobDetail getDetail;

    JobController(CurrentActorProvider actors, ListOwnJobs listOwn, CompleteJob completeJob, ProgressJob progressJob,
            AddJobEvidence addEvidence, GetJobDetail getDetail) {
        this.actors = actors;
        this.listOwn = listOwn;
        this.completeJob = completeJob;
        this.progressJob = progressJob;
        this.addEvidence = addEvidence;
        this.getDetail = getDetail;
    }

    @GetMapping("/me")
    @Operation(summary = "List the jobs assigned to the current fixer")
    @ApiResponse(responseCode = "200", description = "Jobs of the current fixer, newest first")
    List<JobResponse> mine() {
        return listOwn.cards(actors.currentActor()).stream()
                .map(card -> JobResponse.of(card.job(), card.requestTitle(), card.specialty(), card.propertyName(),
                        card.propertyAddress(), card.propertyCity(), card.ownerName()))
                .toList();
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Read a job with its log and evidence photos (FR-UC-19)")
    @ApiResponse(responseCode = "200", description = "Job, status log and evidence")
    JobDetailResponse detail(@PathVariable UUID jobId) {
        return JobDetailResponse.of(getDetail.byId(actors.currentActor(), jobId));
    }

    @GetMapping("/for-request/{requestId}")
    @Operation(summary = "Read the job opened for a repair request (FR-UC-19)")
    @ApiResponse(responseCode = "200", description = "Job, status log and evidence")
    JobDetailResponse forRequest(@PathVariable UUID requestId) {
        return JobDetailResponse.of(getDetail.byRequest(actors.currentActor(), requestId));
    }

    @PostMapping(value = "/{jobId}/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set or move the date agreed with the client")
    @ApiResponse(responseCode = "200", description = "Scheduled job")
    JobResponse schedule(@PathVariable UUID jobId, @Valid @RequestBody ScheduleRequest body) {
        return JobResponse.of(progressJob.schedule(actors.currentActor(), jobId, body.scheduledFor(), body.note()));
    }

    @PostMapping(value = "/{jobId}/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Mark the job as in progress")
    @ApiResponse(responseCode = "200", description = "Job in progress")
    JobResponse start(@PathVariable UUID jobId, @Valid @RequestBody NoteRequest body) {
        return JobResponse.of(progressJob.start(actors.currentActor(), jobId, body.note()));
    }

    @PostMapping(value = "/{jobId}/evidence", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Attach before, during or after photos to the job")
    @ApiResponse(responseCode = "200", description = "Attached evidence")
    List<EvidenceResponse> evidence(@PathVariable UUID jobId, @Valid @RequestBody EvidenceRequest body) {
        return addEvidence.execute(actors.currentActor(), jobId, body.mediaIds(), body.stage(), body.caption()).stream()
                .map(item -> new EvidenceResponse(item.id(), item.mediaId(), null, item.stage(), item.caption(),
                        item.createdAt()))
                .toList();
    }

    @PostMapping("/{jobId}/complete")
    @Operation(summary = "Close a finished job",
            description = "Closes the request and records the work in the property's history. CASH requires receivedAmount; "
                    + "TRANSFER rejects it. Only the assigned fixer can close his own job.")
    @ApiResponse(responseCode = "200", description = "The job is completed")
    JobResponse complete(@PathVariable UUID jobId, @Valid @RequestBody(required = false) CompletionRequest body) {
        return JobResponse.of(completeJob.execute(actors.currentActor(), jobId,
                body == null ? null : body.receivedAsLong(), body == null ? null : body.note()));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record CompletionRequest(@Schema(type = "integer", format = "int64") JsonNode receivedAmount,
            @Size(max = 1000) String note) {
        Long receivedAsLong() {
            if (receivedAmount == null || receivedAmount.isNull()) {
                return null;
            }
            if (receivedAmount.isTextual()) {
                try {
                    return new BigDecimal(receivedAmount.textValue()).longValueExact();
                } catch (NumberFormatException | ArithmeticException ignored) {
                    throw new com.fixup.jobs.api.JobConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                            "Cash received must be a whole peso amount");
                }
            }
            if (!receivedAmount.isIntegralNumber() || !receivedAmount.canConvertToLong()) {
                throw new com.fixup.jobs.api.JobConflictException("INVALID_CASH_RECEIVED_AMOUNT",
                        "Cash received must be a whole peso amount");
            }
            return receivedAmount.longValue();
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record ScheduleRequest(@NotNull Instant scheduledFor, @Size(max = 1000) String note) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record NoteRequest(@Size(max = 1000) String note) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record EvidenceRequest(@NotEmpty @Size(max = 8) List<@NotNull UUID> mediaIds, @NotNull JobEvidence.Stage stage,
            @Size(max = 200) String caption) {
    }

    @Schema(requiredProperties = {"id", "requestId", "quotationId", "ownerUserId", "paymentMethod", "agreedAmount", "status", "createdAt"})
    record JobResponse(UUID id, UUID requestId, UUID quotationId, UUID ownerUserId,
            String paymentMethod, long agreedAmount, JobStatus status,
            Instant createdAt, @Schema(types = {"string", "null"}) Instant completedAt,
            @Schema(types = {"string", "null"}) Instant scheduledFor,
            @Schema(types = {"string", "null"}) Instant startedAt,
            @Schema(types = {"string", "null"}) String progressNote,
            @Schema(types = {"string", "null"}) String requestTitle,
            @Schema(types = {"string", "null"}) String specialty,
            @Schema(types = {"string", "null"}) String propertyName,
            @Schema(types = {"string", "null"}) String propertyAddress,
            @Schema(types = {"string", "null"}) String propertyCity,
            @Schema(types = {"string", "null"}) String ownerName) {

        static JobResponse of(JobSummary summary) {
            return of(summary, null, null, null, null, null, null);
        }

        static JobResponse of(JobSummary summary, String requestTitle, String specialty, String propertyName,
                String propertyAddress, String propertyCity, String ownerName) {
            return new JobResponse(summary.id(), summary.requestId(), summary.quotationId(),
                    summary.ownerUserId(), summary.paymentMethod(), summary.agreedAmount(),
                    summary.status(), summary.createdAt(), summary.completedAt(),
                    summary.scheduledFor(), summary.startedAt(), summary.progressNote(), requestTitle, specialty,
                    propertyName, propertyAddress, propertyCity, ownerName);
        }
    }

    @Schema(requiredProperties = {"id", "mediaId", "stage", "createdAt"})
    record EvidenceResponse(UUID id, UUID mediaId, @Schema(types = {"string", "null"}) String url,
            JobEvidence.Stage stage, @Schema(types = {"string", "null"}) String caption, Instant createdAt) {
    }

    @Schema(requiredProperties = {"status", "changedAt"})
    record LogEntryResponse(JobStatus status, @Schema(types = {"string", "null"}) String note,
            @Schema(types = {"string", "null"}) String changedBy, Instant changedAt) {
    }

    @Schema(requiredProperties = {"job", "evidence", "log"})
    record JobDetailResponse(JobResponse job, @Schema(types = {"string", "null"}) String fixerName,
            List<EvidenceResponse> evidence, List<LogEntryResponse> log) {

        static JobDetailResponse of(GetJobDetail.JobDetail detail) {
            return new JobDetailResponse(
                    JobResponse.of(detail.job(), detail.requestTitle(), detail.specialty(), detail.propertyName(),
                            detail.propertyAddress(), detail.propertyCity(), detail.ownerName()),
                    detail.fixerName(),
                    detail.evidence().stream().map(item -> new EvidenceResponse(item.id(), item.mediaId(), item.url(),
                            item.stage(), item.caption(), item.createdAt())).toList(),
                    detail.log().stream().map(entry -> new LogEntryResponse(entry.status(), entry.note(),
                            entry.changedBy(), entry.changedAt())).toList());
        }
    }
}
