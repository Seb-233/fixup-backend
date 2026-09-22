package com.fixup.requests.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.fixers.api.Specialty;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.media.api.SignedMediaView;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.RepairRequestUrgency;
import com.fixup.requests.application.CancelRepairRequest;
import com.fixup.requests.application.CreateRepairRequest;
import com.fixup.requests.application.GetRepairRequest;
import com.fixup.requests.application.ListOpenRepairRequests;
import com.fixup.requests.application.ListOwnRepairRequests;
import com.fixup.requests.application.NewRepairRequest;
import com.fixup.requests.application.PutRepairRequestOnHold;
import com.fixup.requests.application.ResumeRepairRequest;
import com.fixup.requests.application.StartRepairProgress;
import com.fixup.requests.application.UpdateRepairRequestUrgency;
import com.fixup.requests.application.RepairRequestSummary;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-18: solicitudes de reparación. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/requests", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid specialty, missing field or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, missing role or request not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The repair request does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "The transition does not apply to the current state of the request",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class RepairRequestController {
    private final CurrentActorProvider actors;
    private final CreateRepairRequest createRequest;
    private final ListOwnRepairRequests listOwn;
    private final ListOpenRepairRequests listOpen;
    private final GetRepairRequest getRequest;
    private final UpdateRepairRequestUrgency updateUrgency;
    private final StartRepairProgress startProgress;
    private final PutRepairRequestOnHold putOnHold;
    private final ResumeRepairRequest resumeRequest;
    private final CancelRepairRequest cancelRequest;
    private final MediaAttachmentService mediaAttachmentService;

    RepairRequestController(CurrentActorProvider actors, CreateRepairRequest createRequest,
            ListOwnRepairRequests listOwn, ListOpenRepairRequests listOpen, GetRepairRequest getRequest,
            UpdateRepairRequestUrgency updateUrgency, StartRepairProgress startProgress,
            PutRepairRequestOnHold putOnHold, ResumeRepairRequest resumeRequest,
            CancelRepairRequest cancelRequest, MediaAttachmentService mediaAttachmentService) {
        this.actors = actors;
        this.createRequest = createRequest;
        this.listOwn = listOwn;
        this.listOpen = listOpen;
        this.getRequest = getRequest;
        this.updateUrgency = updateUrgency;
        this.startProgress = startProgress;
        this.putOnHold = putOnHold;
        this.resumeRequest = resumeRequest;
        this.cancelRequest = cancelRequest;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(com.fixup.properties.api.PropertyNotFoundException.class)
    org.springframework.http.ResponseEntity<ErrorResponse> handlePropertyNotFound(jakarta.servlet.http.HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, "NOT_FOUND", "The property does not exist", request.getRequestURI()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open a repair request",
            description = "The body carries media asset IDs. No raw image binary content crosses this API.")
    @ApiResponse(responseCode = "201", description = "The request is open and visible to the fixers")
    @ResponseStatus(HttpStatus.CREATED)
    RequestDetailResponse open(@Valid @RequestBody OpenRequest body) {
        var mediaIds = body.mediaIds() == null ? List.<UUID>of() : body.mediaIds();
        var summary = createRequest.execute(actors.currentActor(),
                new NewRepairRequest(body.propertyId(), body.title(), body.description(), mediaIds,
                        body.urgency()));
        var photos = mediaAttachmentService.resolveReadUrls(summary.mediaIds());
        return RequestDetailResponse.of(summary, photos);
    }

    @GetMapping("/me")
    @Operation(summary = "List the repair requests opened by the current user")
    @ApiResponse(responseCode = "200", description = "Requests owned by the current user, newest first")
    List<RequestDetailResponse> mine() {
        return listOwn.execute(actors.currentActor()).stream()
                .map(request -> {
                    var photos = mediaAttachmentService.resolveReadUrls(request.mediaIds());
                    return RequestDetailResponse.of(request, photos);
                })
                .toList();
    }

    @GetMapping("/open")
    @Operation(summary = "List the open requests offered to fixers",
            description = "FR-UC-18: the fixer's inbox. Requires active, verified FIXER and filters by fixer specialties.")
    @ApiResponse(responseCode = "200", description = "Open requests matching fixer specialties, newest first")
    List<OpenRequestSummaryResponse> open() {
        return listOpen.execute(actors.currentActor()).stream().map(OpenRequestSummaryResponse::of).toList();
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Read one repair request",
            description = "The owner always reads it; a verified compatible fixer reads it while open, or afterwards "
                    + "only when the work was assigned to him.")
    @ApiResponse(responseCode = "200", description = "The request with its description and photos")
    RequestDetailResponse detail(@PathVariable UUID requestId) {
        var request = getRequest.execute(actors.currentActor(), requestId);
        var photos = mediaAttachmentService.resolveReadUrls(request.mediaIds());
        return RequestDetailResponse.of(request, photos);
    }

    @PatchMapping(value = "/{requestId}/urgency", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Change the urgency of a repair request",
            description = "FR-UC-08: reclassifying the urgency moves the committed SLA deadline, which is measured "
                    + "from the moment the request was opened.")
    @ApiResponse(responseCode = "200", description = "The request with its new urgency and deadline")
    RequestDetailResponse changeUrgency(@PathVariable UUID requestId,
            @Valid @RequestBody UrgencyRequest body) {
        return detailOf(updateUrgency.execute(actors.currentActor(), requestId, body.urgency()));
    }

    @PostMapping("/{requestId}/start")
    @Operation(summary = "Start the repair work",
            description = "FR-UC-08: only the assigned fixer, and only from ASSIGNED.")
    @ApiResponse(responseCode = "200", description = "The request is now in progress")
    RequestDetailResponse start(@PathVariable UUID requestId) {
        return detailOf(startProgress.execute(actors.currentActor(), requestId));
    }

    @PostMapping("/{requestId}/hold")
    @Operation(summary = "Put the repair work on hold",
            description = "FR-UC-08: either the owner or the assigned fixer, from ASSIGNED or IN_PROGRESS.")
    @ApiResponse(responseCode = "200", description = "The request is on hold")
    RequestDetailResponse hold(@PathVariable UUID requestId) {
        return detailOf(putOnHold.execute(actors.currentActor(), requestId));
    }

    @PostMapping("/{requestId}/resume")
    @Operation(summary = "Resume a repair request that was on hold",
            description = "FR-UC-08: either the owner or the assigned fixer, only from ON_HOLD.")
    @ApiResponse(responseCode = "200", description = "The request is in progress again")
    RequestDetailResponse resume(@PathVariable UUID requestId) {
        return detailOf(resumeRequest.execute(actors.currentActor(), requestId));
    }

    @PostMapping("/{requestId}/cancel")
    @Operation(summary = "Cancel a repair request",
            description = "FR-UC-08: only its owner, and never one that is already completed or cancelled. "
                    + "Completing a request is not a route of its own: it happens when the fixer closes the job "
                    + "through POST /jobs/{jobId}/complete, which is what releases the escrow.")
    @ApiResponse(responseCode = "200", description = "The request is cancelled")
    RequestDetailResponse cancel(@PathVariable UUID requestId) {
        return detailOf(cancelRequest.execute(actors.currentActor(), requestId));
    }

    private RequestDetailResponse detailOf(RepairRequestSummary summary) {
        return RequestDetailResponse.of(summary, mediaAttachmentService.resolveReadUrls(summary.mediaIds()));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record UrgencyRequest(@NotNull RepairRequestUrgency urgency) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record OpenRequest(@NotNull UUID propertyId, @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 6) List<@NotNull UUID> mediaIds,
            @Schema(description = "FR-UC-08: optional; the request opens as MEDIUM when absent")
            RepairRequestUrgency urgency) {
        public OpenRequest {
            if (mediaIds != null) {
                if (mediaIds.contains(null)) {
                    throw new IllegalArgumentException("mediaIds cannot contain null elements");
                }
                if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
                    throw new IllegalArgumentException("mediaIds cannot contain duplicates");
                }
            }
        }
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "createdAt"})
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record OpenRequestSummaryResponse(UUID requestId, Specialty specialty, String title, Instant createdAt) {
        static OpenRequestSummaryResponse of(com.fixup.requests.domain.RepairRequest request) {
            return new OpenRequestSummaryResponse(request.id(), request.specialty(), request.title(), request.createdAt());
        }
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "description", "photos",
        "status", "urgency", "createdAt"})
    record RequestDetailResponse(UUID requestId, UUID propertyId, Specialty specialty, String title, String description,
            List<PhotoResponse> photos, RepairRequestStatus status,
            RepairRequestUrgency urgency,
            @Schema(types = {"string", "null"}) Instant slaDeadline,
            @Schema(types = {"string", "null"}) UUID assignedFixerUserId, Instant createdAt) {

        static RequestDetailResponse of(com.fixup.requests.domain.RepairRequest request,
                List<SignedMediaView> photos) {
            var photoResponses = photos == null ? List.<PhotoResponse>of() : photos.stream()
                    .map(p -> new PhotoResponse(p.mediaId(), p.readUrl(), p.readUrlExpiresAt()))
                    .toList();
            return new RequestDetailResponse(request.id(), request.propertyId(), request.specialty(), request.title(), request.description(),
                    photoResponses, request.status(), request.urgency(), request.slaDeadline(),
                    request.assignedFixerUserId(), request.createdAt());
        }

        static RequestDetailResponse of(RepairRequestSummary summary,
                List<SignedMediaView> photos) {
            var photoResponses = photos == null ? List.<PhotoResponse>of() : photos.stream()
                    .map(p -> new PhotoResponse(p.mediaId(), p.readUrl(), p.readUrlExpiresAt()))
                    .toList();
            return new RequestDetailResponse(summary.id(), summary.propertyId(), summary.specialty(), summary.title(), summary.description(),
                    photoResponses, summary.status(), summary.urgency(), summary.slaDeadline(),
                    summary.assignedFixerUserId(), summary.createdAt());
        }
    }

    @Schema(requiredProperties = {"mediaId", "readUrl", "readUrlExpiresAt"})
    record PhotoResponse(UUID mediaId, String readUrl, Instant readUrlExpiresAt) {
    }
}
