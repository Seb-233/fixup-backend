package com.fixup.ratings.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.ratings.application.RateService;
import com.fixup.ratings.application.ReadRatings;
import com.fixup.ratings.domain.ServiceRating;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-09: calificación de servicios y reseñas públicas de los técnicos. */
@RestController
@RequestMapping(value = "/ratings", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Not the client of the job",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Job not completed or already rated",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class RatingController {
    private final CurrentActorProvider actors;
    private final RateService rate;
    private final ReadRatings read;

    RatingController(CurrentActorProvider actors, RateService rate, ReadRatings read) {
        this.actors = actors;
        this.rate = rate;
        this.read = read;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rate a completed job from 1 to 5 with an optional review")
    @ResponseStatus(HttpStatus.CREATED)
    RatingResponse rate(@Valid @RequestBody RateRequest body) {
        return RatingResponse.of(rate.execute(actors.currentActor(), body.jobId(), body.score(), body.punctuality(),
                body.quality(), body.cleanliness(), body.comment()), Map.of());
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "The rating of a job, if it was already rated")
    ResponseEntity<RatingResponse> forJob(@PathVariable UUID jobId) {
        return read.forJob(actors.currentActor(), jobId)
                .map(rating -> ResponseEntity.ok(RatingResponse.of(rating, Map.of())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/fixers/{fixerUserId}")
    @Operation(summary = "Public reviews of a fixer with the score distribution")
    FixerReviewsResponse forFixer(@PathVariable UUID fixerUserId, @RequestParam(defaultValue = "20") int limit) {
        var result = read.forFixer(actors.currentActor(), fixerUserId, limit);
        var reputation = result.reputation();
        return new FixerReviewsResponse(fixerUserId, reputation.average(), reputation.count(),
                List.of(reputation.oneStar(), reputation.twoStars(), reputation.threeStars(), reputation.fourStars(),
                        reputation.fiveStars()),
                result.reviews().stream().map(rating -> RatingResponse.of(rating, result.raterNames())).toList());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record RateRequest(@NotNull UUID jobId, @NotNull @Min(1) @Max(5) Integer score, @Min(1) @Max(5) Integer punctuality,
            @Min(1) @Max(5) Integer quality, @Min(1) @Max(5) Integer cleanliness, @Size(max = 1000) String comment) {
    }

    @Schema(requiredProperties = {"id", "jobId", "fixerUserId", "score", "createdAt"})
    record RatingResponse(UUID id, UUID jobId, UUID fixerUserId, int score,
            @Schema(types = {"integer", "null"}) Integer punctuality,
            @Schema(types = {"integer", "null"}) Integer quality,
            @Schema(types = {"integer", "null"}) Integer cleanliness,
            @Schema(types = {"string", "null"}) String comment,
            @Schema(types = {"string", "null"}) String raterName, Instant createdAt) {

        static RatingResponse of(ServiceRating rating, Map<UUID, String> names) {
            return new RatingResponse(rating.id(), rating.jobId(), rating.fixerUserId(), rating.score(),
                    rating.punctuality(), rating.quality(), rating.cleanliness(), rating.comment(),
                    names.get(rating.raterUserId()), rating.createdAt());
        }
    }

    @Schema(requiredProperties = {"fixerUserId", "ratingCount", "distribution", "reviews"})
    record FixerReviewsResponse(UUID fixerUserId, @Schema(types = {"number", "null"}) BigDecimal ratingAverage,
            int ratingCount, @Schema(description = "Counts for 1 to 5 stars, in that order") List<Integer> distribution,
            List<RatingResponse> reviews) {
    }
}
