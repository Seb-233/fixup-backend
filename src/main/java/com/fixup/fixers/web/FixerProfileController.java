package com.fixup.fixers.web;

import com.fixup.fixers.api.FixerDirectory;
import com.fixup.fixers.api.FixerNotEligibleException;
import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.application.ListPendingVerifications;
import com.fixup.fixers.application.UpdateFixerPresentation;
import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
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
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-UC-09 y FR-UC-16: perfil público y directorio de técnicos verificados. El perfil de un técnico
 * que todavía no está verificado solo lo ven él mismo y un administrador de plataforma.
 */
@RestController
@RequestMapping(value = "/fixers", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or profile not visible to the caller",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class FixerProfileController {
    private final CurrentActorProvider actors;
    private final FixerDirectory directory;
    private final UpdateFixerPresentation updatePresentation;
    private final ListPendingVerifications pendingVerifications;

    FixerProfileController(CurrentActorProvider actors, FixerDirectory directory,
            UpdateFixerPresentation updatePresentation, ListPendingVerifications pendingVerifications) {
        this.actors = actors;
        this.directory = directory;
        this.updatePresentation = updatePresentation;
        this.pendingVerifications = pendingVerifications;
    }

    @GetMapping("/directory")
    @Operation(summary = "Verified fixers, best rated first, optionally by specialty and city")
    List<FixerProfileResponse> directory(@RequestParam(required = false) Specialty specialty,
            @RequestParam(required = false) String city) {
        requireActive(actors.currentActor());
        return directory.verified(specialty, city).stream().map(FixerProfileResponse::of).toList();
    }

    @GetMapping("/{fixerUserId}/profile")
    @Operation(summary = "Public profile of one fixer: specialties, city, experience and rating")
    FixerProfileResponse profile(@PathVariable UUID fixerUserId) {
        var actor = actors.currentActor();
        requireActive(actor);
        var listing = directory.find(fixerUserId).orElseThrow(FixerNotEligibleException::new);
        boolean visible = listing.status() == FixerVerificationStatus.VERIFIED
                || fixerUserId.equals(actor.internalUserId()) || actor.hasRole(Role.PLATFORM_ADMIN);
        if (!visible) {
            throw new FixerNotEligibleException();
        }
        return FixerProfileResponse.of(listing);
    }

    @GetMapping("/me/profile")
    @Operation(summary = "The current fixer's own public profile")
    FixerProfileResponse myProfile() {
        var actor = actors.currentActor();
        requireActive(actor);
        if (!actor.hasRole(Role.FIXER)) {
            throw new FixerNotEligibleException();
        }
        return directory.find(actor.internalUserId()).map(FixerProfileResponse::of)
                .orElseThrow(FixerNotEligibleException::new);
    }

    @PutMapping(value = "/me/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update the city, short bio and years of experience shown to clients")
    FixerProfileResponse updateMyProfile(@Valid @RequestBody PresentationRequest body) {
        return FixerProfileResponse.of(updatePresentation.execute(actors.currentActor(), body.city(), body.bio(),
                body.yearsExperience()));
    }

    @GetMapping("/verification/pending")
    @Operation(summary = "Review queue: fixers who submitted documents and await a decision (FR-UC-16)")
    List<PendingVerificationResponse> pending() {
        return pendingVerifications.execute(actors.currentActor()).stream()
                .map(item -> new PendingVerificationResponse(item.fixerUserId(), item.displayName(),
                        item.specialties(), item.submittedAt()))
                .toList();
    }

    private static void requireActive(CurrentActor actor) {
        if (actor.status() != UserStatus.ACTIVE) {
            throw new FixerNotEligibleException();
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    record PresentationRequest(@Size(max = 100) String city, @Size(max = 600) String bio,
            @Min(0) @Max(70) Integer yearsExperience) {
    }

    @Schema(requiredProperties = {"fixerUserId", "displayName", "verificationStatus", "specialties", "ratingCount"})
    record FixerProfileResponse(UUID fixerUserId, String displayName, FixerVerificationStatus verificationStatus,
            Set<Specialty> specialties, @Schema(types = {"string", "null"}) String city,
            @Schema(types = {"string", "null"}) String bio,
            @Schema(types = {"integer", "null"}) Integer yearsExperience,
            @Schema(types = {"number", "null"}) BigDecimal ratingAverage, int ratingCount) {

        static FixerProfileResponse of(FixerDirectory.FixerListing listing) {
            return new FixerProfileResponse(listing.userId(), listing.displayName(), listing.status(),
                    listing.specialties(), listing.city(), listing.bio(), listing.yearsExperience(),
                    listing.ratingAverage(), listing.ratingCount());
        }
    }

    @Schema(requiredProperties = {"fixerUserId", "displayName", "specialties", "submittedAt"})
    record PendingVerificationResponse(UUID fixerUserId, String displayName, Set<Specialty> specialties,
            Instant submittedAt) {
    }
}
