package com.fixup.jobs.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.jobs.application.RankMaintenanceCandidates;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-11: técnicos sugeridos para una solicitud de mantenimiento. */
@RestController
@RequestMapping(value = "/management/requests", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Not the owner or manager of the property",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class MaintenanceCandidatesController {
    private final CurrentActorProvider actors;
    private final RankMaintenanceCandidates rank;

    MaintenanceCandidatesController(CurrentActorProvider actors, RankMaintenanceCandidates rank) {
        this.actors = actors;
        this.rank = rank;
    }

    @GetMapping("/{requestId}/candidates")
    @Operation(summary = "Suggested fixers ranked by city, availability (open jobs) and rating")
    List<CandidateResponse> candidates(@PathVariable UUID requestId) {
        return rank.execute(actors.currentActor(), requestId).stream()
                .map(candidate -> new CandidateResponse(candidate.fixerUserId(), candidate.displayName(),
                        candidate.city(), candidate.sameCity(), candidate.openJobs(), candidate.ratingAverage(),
                        candidate.ratingCount(), candidate.yearsExperience(), candidate.score(), candidate.invited()))
                .toList();
    }

    @Schema(requiredProperties = {"fixerUserId", "displayName", "sameCity", "openJobs", "ratingCount", "score",
        "invited"})
    record CandidateResponse(UUID fixerUserId, String displayName, @Schema(types = {"string", "null"}) String city,
            boolean sameCity, long openJobs, @Schema(types = {"number", "null"}) BigDecimal ratingAverage,
            int ratingCount, @Schema(types = {"integer", "null"}) Integer yearsExperience, double score,
            boolean invited) {
    }
}
