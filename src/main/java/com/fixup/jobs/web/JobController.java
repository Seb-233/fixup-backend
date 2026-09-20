package com.fixup.jobs.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.jobs.api.JobStatus;
import com.fixup.jobs.application.CompleteJob;
import com.fixup.jobs.application.JobSummary;
import com.fixup.jobs.application.ListOwnJobs;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-20: trabajos del técnico. Controllers stay thin and never touch JPA. */
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

    JobController(CurrentActorProvider actors, ListOwnJobs listOwn, CompleteJob completeJob) {
        this.actors = actors;
        this.listOwn = listOwn;
        this.completeJob = completeJob;
    }

    @GetMapping("/me")
    @Operation(summary = "List the jobs assigned to the current fixer")
    @ApiResponse(responseCode = "200", description = "Jobs of the current fixer, newest first")
    List<JobResponse> mine() {
        return listOwn.execute(actors.currentActor()).stream().map(JobResponse::of).toList();
    }

    @PostMapping("/{jobId}/complete")
    @Operation(summary = "Close a finished job",
            description = "Releases from escrow the amount the owner committed when accepting the "
                    + "quotation. Only the assigned fixer can close his own job.")
    @ApiResponse(responseCode = "200", description = "The job is completed and the earning released")
    JobResponse complete(@PathVariable UUID jobId) {
        return JobResponse.of(completeJob.execute(actors.currentActor(), jobId));
    }

    @Schema(requiredProperties = {"id", "requestId", "quotationId", "ownerUserId", "status", "createdAt"})
    record JobResponse(UUID id, UUID requestId, UUID quotationId, UUID ownerUserId, JobStatus status,
            Instant createdAt, @Schema(types = {"string", "null"}) Instant completedAt) {

        static JobResponse of(JobSummary summary) {
            return new JobResponse(summary.id(), summary.requestId(), summary.quotationId(),
                    summary.ownerUserId(), summary.status(), summary.createdAt(), summary.completedAt());
        }
    }
}
