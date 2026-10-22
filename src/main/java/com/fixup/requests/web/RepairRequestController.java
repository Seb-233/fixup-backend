package com.fixup.requests.web;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.fixers.api.Specialty;
import com.fixup.media.api.MediaAttachmentService;
import com.fixup.media.api.SignedMediaView;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.SlaState;
import com.fixup.requests.api.UrgencyLevel;
import com.fixup.requests.application.CancelRepairRequest;
import com.fixup.requests.application.CreateRepairRequest;
import com.fixup.requests.application.GetRepairRequest;
import com.fixup.requests.application.ListOpenRepairRequests;
import com.fixup.requests.application.ListOwnRepairRequests;
import com.fixup.requests.application.ListReportableProperties;
import com.fixup.requests.application.NewRepairRequest;
import com.fixup.requests.application.RepairRequestSummary;
import com.fixup.requests.application.RequestPeople;
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
import java.util.Map;
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

/** FR-UC-04, FR-UC-08 y FR-UC-18: solicitudes de reparación. Controllers stay thin and never touch JPA. */
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
    private final CancelRepairRequest cancelRequest;
    private final ListReportableProperties reportableProperties;
    private final RequestPeople people;
    private final MediaAttachmentService mediaAttachmentService;

    RepairRequestController(CurrentActorProvider actors, CreateRepairRequest createRequest,
            ListOwnRepairRequests listOwn, ListOpenRepairRequests listOpen, GetRepairRequest getRequest,
            CancelRepairRequest cancelRequest, ListReportableProperties reportableProperties,
            RequestPeople people, MediaAttachmentService mediaAttachmentService) {
        this.actors = actors;
        this.createRequest = createRequest;
        this.listOwn = listOwn;
        this.listOpen = listOpen;
        this.getRequest = getRequest;
        this.cancelRequest = cancelRequest;
        this.reportableProperties = reportableProperties;
        this.people = people;
        this.mediaAttachmentService = mediaAttachmentService;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(com.fixup.properties.api.PropertyNotFoundException.class)
    org.springframework.http.ResponseEntity<ErrorResponse> handlePropertyNotFound(jakarta.servlet.http.HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(404, "NOT_FOUND", "The property does not exist", request.getRequestURI()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open a repair request",
            description = "The owner, the property's real estate manager or a tenant with an active lease may open it "
                    + "(FR-UC-08). The body carries media asset IDs. No raw image binary content crosses this API.")
    @ApiResponse(responseCode = "201", description = "The request is open and visible to the fixers")
    @ResponseStatus(HttpStatus.CREATED)
    RequestDetailResponse open(@Valid @RequestBody OpenRequest body) {
        var actor = actors.currentActor();
        var mediaIds = body.mediaIds() == null ? List.<UUID>of() : body.mediaIds();
        var summary = createRequest.execute(actor,
                new NewRepairRequest(body.propertyId(), body.title(), body.description(), mediaIds,
                        body.urgencyLevel(), body.paymentMethod()));
        return detail(actor, summary, people.namesFor(List.of(summary)), true);
    }

    @GetMapping("/me")
    @Operation(summary = "List the repair requests of the properties the user owns, rents or manages")
    @ApiResponse(responseCode = "200", description = "Requests related to the current user, newest first")
    List<RequestDetailResponse> mine() {
        var actor = actors.currentActor();
        var requests = listOwn.execute(actor);
        var names = people.namesFor(requests);
        return requests.stream().map(request -> detail(actor, request, names, true)).toList();
    }

    @GetMapping("/reportable-properties")
    @Operation(summary = "Properties the current tenant rents with an active lease (FR-UC-08)")
    List<ReportablePropertyResponse> reportableProperties() {
        return reportableProperties.rented(actors.currentActor()).stream()
                .map(property -> new ReportablePropertyResponse(property.propertyId(), property.name(),
                        property.address(), property.city()))
                .toList();
    }

    @GetMapping("/open")
    @Operation(summary = "List the open requests offered to fixers",
            description = "FR-UC-18: the fixer's inbox. Requires active, verified FIXER and filters by fixer specialties "
                    + "and city. Requests a manager invited the fixer to (FR-UC-11) come first, from any city.")
    @ApiResponse(responseCode = "200", description = "Open requests matching fixer specialties")
    List<OpenRequestSummaryResponse> open() {
        var inbox = listOpen.inbox(actors.currentActor());
        return inbox.requests().stream()
                .map(request -> OpenRequestSummaryResponse.of(request, inbox.invitedRequestIds().contains(request.id())))
                .toList();
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Read one repair request",
            description = "The people related to the property always read it; a verified compatible fixer reads it "
                    + "while open, or afterwards only when the work was assigned to him.")
    @ApiResponse(responseCode = "200", description = "The request with its description and photos")
    RequestDetailResponse detail(@PathVariable UUID requestId) {
        var actor = actors.currentActor();
        var request = RepairRequestSummary.of(getRequest.execute(actor, requestId));
        return detail(actor, request, people.namesFor(List.of(request)), true);
    }

    @PostMapping("/{requestId}/cancel")
    @Operation(summary = "Withdraw an open request nobody has taken yet")
    @ApiResponse(responseCode = "200", description = "The request is cancelled")
    RequestDetailResponse cancel(@PathVariable UUID requestId) {
        var actor = actors.currentActor();
        var request = cancelRequest.execute(actor, requestId);
        return detail(actor, request, people.namesFor(List.of(request)), true);
    }

    private RequestDetailResponse detail(CurrentActor actor, RepairRequestSummary request, Map<UUID, String> names,
            boolean withPhotos) {
        var photos = withPhotos ? mediaAttachmentService.resolveReadUrls(request.mediaIds()) : List.<SignedMediaView>of();
        return RequestDetailResponse.of(request, photos, names, canSeeAddress(actor, request));
    }

    /**
     * La dirección exacta solo la ven quienes tienen relación con el inmueble y el técnico asignado;
     * mientras la solicitud está abierta, los técnicos ven solo ciudad y barrio.
     */
    private static boolean canSeeAddress(CurrentActor actor, RepairRequestSummary request) {
        var userId = actor.internalUserId();
        return actor.hasRole(Role.PLATFORM_ADMIN) || userId.equals(request.ownerUserId())
                || userId.equals(request.requestedByUserId()) || userId.equals(request.managerUserId())
                || userId.equals(request.assignedFixerUserId());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record OpenRequest(@NotNull UUID propertyId, @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 6) List<@NotNull UUID> mediaIds,
            UrgencyLevel urgencyLevel,
            @Schema(description = "TRANSFER or CASH. Required from the owner or the manager; a tenant's report is "
                    + "always paid by transfer (FR-UC-05).") PaymentMethod paymentMethod) {
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

    @Schema(requiredProperties = {"requestId", "specialty", "title", "paymentMethod", "createdAt", "urgencyLevel",
        "invited"})
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record OpenRequestSummaryResponse(UUID requestId, Specialty specialty, String title,
            PaymentMethod paymentMethod, Instant createdAt,
            @Schema(types = {"string", "null"}) String propertyCity, UrgencyLevel urgencyLevel,
            @Schema(types = {"string", "null"}) Instant slaDeadline, boolean invited) {
        static OpenRequestSummaryResponse of(com.fixup.requests.domain.RepairRequest request, boolean invited) {
            return new OpenRequestSummaryResponse(request.id(), request.specialty(), request.title(),
                    request.paymentMethod(), request.createdAt(), request.propertyCity(), request.urgencyLevel(),
                    request.slaDeadline(), invited);
        }
    }

    @Schema(requiredProperties = {"propertyId", "name", "address", "city"})
    record ReportablePropertyResponse(UUID propertyId, String name, String address, String city) {
    }

    @Schema(requiredProperties = {"requestId", "specialty", "title", "description", "photos",
        "status", "paymentMethod", "urgencyLevel", "createdAt", "slaState", "ownerUserId"})
    record RequestDetailResponse(UUID requestId, UUID propertyId, Specialty specialty, String title, String description,
            List<PhotoResponse> photos, RepairRequestStatus status,
            @Schema(types = {"string", "null"}) UUID assignedFixerUserId, PaymentMethod paymentMethod, Instant createdAt,
            UrgencyLevel urgencyLevel,
            @Schema(types = {"string", "null"}) Instant slaDeadline,
            SlaState slaState,
            UUID ownerUserId,
            @Schema(types = {"string", "null"}) String ownerName,
            @Schema(types = {"string", "null"}) UUID requestedByUserId,
            @Schema(types = {"string", "null"}) String requestedByName,
            @Schema(types = {"string", "null"}) UUID managerUserId,
            @Schema(types = {"string", "null"}) String assignedFixerName,
            @Schema(types = {"string", "null"}) String propertyName,
            @Schema(types = {"string", "null"}) String propertyAddress,
            @Schema(types = {"string", "null"}) String propertyCity,
            @Schema(types = {"string", "null"}) Instant completedAt) {

        static RequestDetailResponse of(RepairRequestSummary summary, List<SignedMediaView> photos,
                Map<UUID, String> names, boolean withAddress) {
            var photoResponses = photos == null ? List.<PhotoResponse>of() : photos.stream()
                    .map(p -> new PhotoResponse(p.mediaId(), p.readUrl(), p.readUrlExpiresAt()))
                    .toList();
            return new RequestDetailResponse(summary.id(), summary.propertyId(), summary.specialty(), summary.title(),
                    summary.description(), photoResponses, summary.status(), summary.assignedFixerUserId(),
                    summary.paymentMethod(), summary.createdAt(), summary.urgencyLevel(), summary.slaDeadline(),
                    summary.slaState(), summary.ownerUserId(), names.get(summary.ownerUserId()),
                    summary.requestedByUserId(), names.get(summary.requestedByUserId()), summary.managerUserId(),
                    names.get(summary.assignedFixerUserId()), summary.propertyName(),
                    withAddress ? summary.propertyAddress() : null, summary.propertyCity(), summary.completedAt());
        }
    }

    @Schema(requiredProperties = {"mediaId", "readUrl", "readUrlExpiresAt"})
    record PhotoResponse(UUID mediaId, String readUrl, Instant readUrlExpiresAt) {
    }
}
