package com.fixup.analytics.web;

import com.fixup.analytics.api.IndicatorFreshness;
import com.fixup.analytics.api.IndicatorSource;
import com.fixup.analytics.application.GetMarketIndicators;
import com.fixup.analytics.application.MarketIndicatorsView;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-UC-15: indicadores del mercado inmobiliario. Controllers stay thin and never touch JPA. */
@RestController
@RequestMapping(value = "/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "The zone is missing or too long",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "503", description = "The external source failed and no known value exists",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class MarketIndicatorsController {
    private final CurrentActorProvider actors;
    private final GetMarketIndicators getIndicators;

    MarketIndicatorsController(CurrentActorProvider actors, GetMarketIndicators getIndicators) {
        this.actors = actors;
        this.getIndicators = getIndicators;
    }

    @GetMapping("/zones/{zone}/market-indicators")
    @Operation(summary = "Read the real estate market indicators of a zone",
            description = "freshness says how the value was obtained: LIVE in this request, CACHED "
                    + "from a still fresh cache entry, DEGRADED when the source did not answer and the "
                    + "last known value is served instead. source says who produced it: "
                    + "EXTERNAL_PROVIDER is a real market observation, DEVELOPMENT_SYNTHETIC is the "
                    + "development fallback, whose numbers are generated and do not represent the "
                    + "market; synthetic repeats that as a flag. observedAt always carries the moment "
                    + "the source produced the value and is never filled in by the backend, so a "
                    + "degraded or synthetic answer is never presented as a current real one.")
    @ApiResponse(responseCode = "200", description = "Indicators of that zone, with their provenance")
    IndicatorsResponse ofZone(@PathVariable @NotBlank @Size(max = 64) String zone) {
        return IndicatorsResponse.of(getIndicators.execute(actors.currentActor(), zone));
    }

    @Schema(requiredProperties = {"zone", "pricePerSquareMeter", "yearOverYearVariationPercent",
        "averageDaysOnMarket", "observedAt", "freshness", "degraded", "source", "synthetic"})
    record IndicatorsResponse(String zone, BigDecimal pricePerSquareMeter,
            BigDecimal yearOverYearVariationPercent, int averageDaysOnMarket, Instant observedAt,
            IndicatorFreshness freshness, boolean degraded, IndicatorSource source,
            boolean synthetic) {

        static IndicatorsResponse of(MarketIndicatorsView view) {
            return new IndicatorsResponse(view.zone(), view.pricePerSquareMeter(),
                    view.yearOverYearVariationPercent(), view.averageDaysOnMarket(),
                    view.observedAt(), view.freshness(), view.degraded(), view.source(),
                    view.synthetic());
        }
    }
}
