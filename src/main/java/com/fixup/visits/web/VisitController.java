package com.fixup.visits.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.shared.errors.ErrorResponse;
import com.fixup.visits.api.ChecklistResult;
import com.fixup.visits.api.VisitKind;
import com.fixup.visits.api.VisitStatus;
import com.fixup.visits.application.DecideVisit;
import com.fixup.visits.application.ListVisits;
import com.fixup.visits.application.ScheduleVisit;
import com.fixup.visits.domain.Visit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-03 y FR-UC-27: agenda de visitas técnicas. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/visits", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Missing field or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Not a party to the request or the visit",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "The visit does not exist",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Invalid date, busy fixer or transition not allowed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class VisitController {
    private final CurrentActorProvider actors;
    private final ScheduleVisit schedule;
    private final DecideVisit decide;
    private final ListVisits list;

    VisitController(CurrentActorProvider actors, ScheduleVisit schedule, DecideVisit decide, ListVisits list) {
        this.actors = actors;
        this.schedule = schedule;
        this.decide = decide;
        this.list = list;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Propose a technical visit for a repair request")
    @ResponseStatus(HttpStatus.CREATED)
    VisitResponse propose(@Valid @RequestBody ProposeRequest body) {
        var visit = schedule.execute(actors.currentActor(), body.requestId(), body.fixerUserId(), body.kind(),
                body.scheduledAt(), body.durationMinutes() == null ? 60 : body.durationMinutes(), body.notes());
        return single(visit.id());
    }

    @GetMapping("/me")
    @Operation(summary = "Visits where the current user takes part, latest first")
    List<VisitResponse> mine() {
        var agenda = list.mine(actors.currentActor());
        return agenda.visits().stream().map(visit -> VisitResponse.of(visit, agenda.names())).toList();
    }

    @GetMapping
    @Operation(summary = "Visits scheduled for one repair request")
    List<VisitResponse> forRequest(@RequestParam UUID requestId) {
        var agenda = list.forRequest(actors.currentActor(), requestId);
        return agenda.visits().stream().map(visit -> VisitResponse.of(visit, agenda.names())).toList();
    }

    @GetMapping("/{visitId}")
    @Operation(summary = "Read one visit")
    VisitResponse one(@PathVariable UUID visitId) {
        return single(visitId);
    }

    @PostMapping("/{visitId}/confirm")
    @Operation(summary = "Confirm the proposed date (the other party only)")
    VisitResponse confirm(@PathVariable UUID visitId) {
        decide.confirm(actors.currentActor(), visitId);
        return single(visitId);
    }

    @PostMapping(value = "/{visitId}/reschedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Propose another date; the other party confirms it")
    VisitResponse reschedule(@PathVariable UUID visitId, @Valid @RequestBody RescheduleRequest body) {
        decide.reschedule(actors.currentActor(), visitId, body.scheduledAt(),
                body.durationMinutes() == null ? 60 : body.durationMinutes());
        return single(visitId);
    }

    @PostMapping(value = "/{visitId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel the visit, telling the other party why")
    VisitResponse cancel(@PathVariable UUID visitId, @Valid @RequestBody CancelRequest body) {
        decide.cancel(actors.currentActor(), visitId, body.reason());
        return single(visitId);
    }

    @PostMapping(value = "/{visitId}/complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The fixer records what the visit found (and the kitchen checklist, FR-UC-27)")
    VisitResponse complete(@PathVariable UUID visitId, @Valid @RequestBody CompleteRequest body) {
        var answers = new HashMap<Integer, Visit.ChecklistEntry>();
        if (body.checklist() != null) {
            body.checklist().forEach(item -> answers.put(item.position(), new Visit.ChecklistEntry(item.result(),
                    item.note())));
        }
        decide.complete(actors.currentActor(), visitId, body.outcome(), answers);
        return single(visitId);
    }

    @PostMapping("/{visitId}/no-show")
    @Operation(summary = "Mark a confirmed visit as missed after its start time")
    VisitResponse noShow(@PathVariable UUID visitId) {
        decide.noShow(actors.currentActor(), visitId);
        return single(visitId);
    }

    private VisitResponse single(UUID visitId) {
        var agenda = list.one(actors.currentActor(), visitId);
        return VisitResponse.of(agenda.visits().get(0), agenda.names());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record ProposeRequest(@NotNull UUID requestId, UUID fixerUserId, @NotNull VisitKind kind,
            @NotNull Instant scheduledAt, @Min(30) @Max(240) Integer durationMinutes, @Size(max = 1000) String notes) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record RescheduleRequest(@NotNull Instant scheduledAt, @Min(30) @Max(240) Integer durationMinutes) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record CompleteRequest(@NotBlank @Size(max = 2000) String outcome, @Size(max = 20) List<@Valid ChecklistAnswer> checklist) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ChecklistAnswer(@NotNull Integer position, @NotNull ChecklistResult result, @Size(max = 500) String note) {
    }

    @Schema(requiredProperties = {"position", "label"})
    record ChecklistItemResponse(int position, String label, @Schema(types = {"string", "null"}) ChecklistResult result,
            @Schema(types = {"string", "null"}) String note) {
    }

    @Schema(requiredProperties = {"id", "requestId", "fixerUserId", "ownerUserId", "proposedByUserId", "kind",
        "status", "scheduledAt", "durationMinutes", "checklist", "createdAt"})
    record VisitResponse(UUID id, UUID requestId, @Schema(types = {"string", "null"}) UUID propertyId,
            UUID fixerUserId, @Schema(types = {"string", "null"}) String fixerName, UUID ownerUserId,
            @Schema(types = {"string", "null"}) String ownerName, UUID proposedByUserId,
            @Schema(types = {"string", "null"}) String proposedByName, VisitKind kind, VisitStatus status,
            Instant scheduledAt, int durationMinutes, @Schema(types = {"string", "null"}) String address,
            @Schema(types = {"string", "null"}) String notes, @Schema(types = {"string", "null"}) String cancelReason,
            @Schema(types = {"string", "null"}) String outcome, List<ChecklistItemResponse> checklist,
            @Schema(types = {"string", "null"}) Instant completedAt, Instant createdAt) {

        static VisitResponse of(Visit visit, Map<UUID, String> names) {
            return new VisitResponse(visit.id(), visit.requestId(), visit.propertyId(), visit.fixerUserId(),
                    names.get(visit.fixerUserId()), visit.ownerUserId(), names.get(visit.ownerUserId()),
                    visit.proposedByUserId(), names.get(visit.proposedByUserId()), visit.kind(), visit.status(),
                    visit.scheduledAt(), visit.durationMinutes(), visit.addressSnapshot(), visit.notes(),
                    visit.cancelReason(), visit.outcome(),
                    visit.checklist().stream().map(item -> new ChecklistItemResponse(item.position(), item.label(),
                            item.result(), item.note())).toList(),
                    visit.completedAt(), visit.createdAt());
        }
    }
}
