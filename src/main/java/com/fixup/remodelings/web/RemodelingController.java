package com.fixup.remodelings.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fixup.fixers.api.Specialty;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.jobs.api.JobStatus;
import com.fixup.remodelings.api.RemodelingProgressStatus;
import com.fixup.remodelings.application.CreateRemodeling;
import com.fixup.remodelings.application.GetRemodeling;
import com.fixup.remodelings.application.ListOwnRemodelings;
import com.fixup.remodelings.application.NewRemodeling;
import com.fixup.remodelings.application.NewRemodelingRequirement;
import com.fixup.remodelings.application.RemodelingProjectDetail;
import com.fixup.remodelings.application.RemodelingProjectSummary;
import com.fixup.remodelings.application.RemodelingRequirementSummary;
import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.requests.api.PaymentMethod;
import com.fixup.requests.api.UrgencyLevel;
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
import java.util.HashSet;
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

@RestController
@RequestMapping(value = "/remodelings", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid project or requirement data",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or role not allowed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Project or property not visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Project invariant conflict",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class RemodelingController {
    private final CurrentActorProvider actors;
    private final CreateRemodeling create;
    private final ListOwnRemodelings listOwn;
    private final GetRemodeling get;

    RemodelingController(CurrentActorProvider actors, CreateRemodeling create,
            ListOwnRemodelings listOwn, GetRemodeling get) {
        this.actors = actors;
        this.create = create;
        this.listOwn = listOwn;
        this.get = get;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a multi-professional remodeling project",
            description = "Requires an active OWNER who directly owns the property. Every need "
                    + "opens an ordinary repair request in the same transaction.")
    @ApiResponse(responseCode = "201", description = "Project and all repair requests created")
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest body) {
        var requirements = body.requirements().stream()
                .map(requirement -> new NewRemodelingRequirement(requirement.title(),
                        requirement.description(), requirement.mediaIds(),
                        requirement.urgencyLevel(), requirement.paymentMethod()))
                .toList();
        return ProjectResponse.of(create.execute(actors.currentActor(),
                new NewRemodeling(body.propertyId(), body.title(), body.description(),
                        requirements)));
    }

    @GetMapping("/me")
    @Operation(summary = "List remodeling projects owned by the current owner")
    @ApiResponse(responseCode = "200", description = "Projects newest first with derived progress")
    List<ProjectSummaryResponse> mine() {
        return listOwn.execute(actors.currentActor()).stream()
                .map(ProjectSummaryResponse::of)
                .toList();
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Read a remodeling project",
            description = "Visible to its owner and, for read-only support, PLATFORM_ADMIN. "
                    + "Fixers continue using the individual request and job APIs.")
    @ApiResponse(responseCode = "200", description = "Project with requirements and derived progress")
    ProjectResponse detail(@PathVariable UUID projectId) {
        return ProjectResponse.of(get.execute(actors.currentActor(), projectId));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @JsonIgnoreProperties(ignoreUnknown = false)
    record CreateProjectRequest(@NotNull UUID propertyId,
            @NotBlank @Size(max = 150) String title,
            @Size(max = 2000) String description,
            @NotEmpty List<@Valid RequirementRequest> requirements) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @JsonIgnoreProperties(ignoreUnknown = false)
    record RequirementRequest(@NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 2000) String description,
            @Size(max = 6) List<@NotNull UUID> mediaIds,
            UrgencyLevel urgencyLevel, @NotNull PaymentMethod paymentMethod) {
        RequirementRequest {
            if (mediaIds != null && new HashSet<>(mediaIds).size() != mediaIds.size()) {
                throw new IllegalArgumentException("mediaIds cannot contain duplicates");
            }
        }
    }

    @Schema(requiredProperties = {"id", "propertyId", "title", "progressStatus",
        "requirementCount", "assignedCount", "completedCount", "createdAt"})
    record ProjectSummaryResponse(UUID id, UUID propertyId, String title,
            RemodelingProgressStatus progressStatus, int requirementCount, int assignedCount,
            int completedCount, Instant createdAt) {
        static ProjectSummaryResponse of(RemodelingProjectSummary summary) {
            return new ProjectSummaryResponse(summary.id(), summary.propertyId(), summary.title(),
                    summary.progressStatus(), summary.requirementCount(), summary.assignedCount(),
                    summary.completedCount(), summary.createdAt());
        }
    }

    @Schema(requiredProperties = {"id", "propertyId", "ownerUserId", "title", "status",
        "requirementCount", "assignedCount", "completedCount", "requirements", "createdAt",
        "updatedAt"})
    record ProjectResponse(UUID id, UUID propertyId, UUID ownerUserId, String title,
            @Schema(types = {"string", "null"}) String description,
            RemodelingProgressStatus status, int requirementCount, int assignedCount,
            int completedCount, List<RequirementResponse> requirements, Instant createdAt,
            Instant updatedAt) {
        static ProjectResponse of(RemodelingProjectDetail detail) {
            return new ProjectResponse(detail.id(), detail.propertyId(), detail.ownerUserId(),
                    detail.title(), detail.description(), detail.progressStatus(),
                    detail.requirementCount(), detail.assignedCount(), detail.completedCount(),
                    detail.requirements().stream().map(RequirementResponse::of).toList(),
                    detail.createdAt(), detail.updatedAt());
        }
    }

    @Schema(requiredProperties = {"requirementId", "requestId", "position", "specialty",
        "requestStatus", "paymentMethod"})
    record RequirementResponse(UUID requirementId, UUID requestId, int position,
            Specialty specialty, RepairRequestStatus requestStatus, PaymentMethod paymentMethod,
            @Schema(types = {"string", "null"}) UUID assignedFixerUserId,
            @Schema(types = {"string", "null"}) UUID jobId,
            @Schema(types = {"string", "null"}) JobStatus jobStatus) {
        static RequirementResponse of(RemodelingRequirementSummary summary) {
            return new RequirementResponse(summary.requirementId(), summary.requestId(),
                    summary.position(), summary.specialty(), summary.requestStatus(), summary.paymentMethod(),
                    summary.assignedFixerUserId(), summary.jobId(), summary.jobStatus());
        }
    }
}
