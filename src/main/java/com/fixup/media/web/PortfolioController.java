package com.fixup.media.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.PortfolioPieceKind;
import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.application.ChangePieceVisibility;
import com.fixup.media.application.GetPublicPortfolio;
import com.fixup.media.application.ListOwnPortfolio;
import com.fixup.media.application.NewPortfolioPiece;
import com.fixup.media.application.PublishPortfolioPiece;
import com.fixup.media.domain.PortfolioPiece;
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

/**
 * FR-UC-17: portafolio visual del técnico. Controllers stay thin and never touch JPA.
 *
 * <p>Known debt, tracked in {@code docs/media-storage.md}: the upload interface is not finished,
 * the storage key the client sends does not yet prove ownership of the stored object, and the
 * response still carries that key instead of a controlled read URL or a media identifier. None of
 * this may reach production as it stands.
 */
@RestController
@RequestMapping(value = "/media", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid kind, missing field or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, or the fixer is not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "A publication rule of the portfolio rejected the operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class PortfolioController {
    private final CurrentActorProvider actors;
    private final PublishPortfolioPiece publishPiece;
    private final ListOwnPortfolio listOwn;
    private final ChangePieceVisibility changeVisibility;
    private final GetPublicPortfolio publicPortfolio;

    PortfolioController(CurrentActorProvider actors, PublishPortfolioPiece publishPiece,
            ListOwnPortfolio listOwn, ChangePieceVisibility changeVisibility,
            GetPublicPortfolio publicPortfolio) {
        this.actors = actors;
        this.publishPiece = publishPiece;
        this.listOwn = listOwn;
        this.changeVisibility = changeVisibility;
        this.publicPortfolio = publicPortfolio;
    }

    @PostMapping("/me/portfolio")
    @Operation(summary = "Publish a piece in the fixer's own portfolio",
            description = "The body carries a storage key only, never media content. The upload "
                    + "service does not exist yet, so the key is accepted as free text and does not "
                    + "prove that the object belongs to this fixer: before production the upload "
                    + "must go through a signed URL issued by the backend and bound to the "
                    + "authenticated fixer. Requires a verified fixer.")
    @ApiResponse(responseCode = "201", description = "The piece was published")
    @ResponseStatus(HttpStatus.CREATED)
    PieceResponse publish(@Valid @RequestBody PieceRequest request) {
        return PieceResponse.of(publishPiece.execute(actors.currentActor(),
                new NewPortfolioPiece(request.kind(), request.storageKey(), request.title(),
                        request.description())));
    }

    @GetMapping("/me/portfolio")
    @Operation(summary = "List the fixer's own portfolio, hidden pieces included")
    @ApiResponse(responseCode = "200", description = "Every piece of the current fixer, in publication order")
    List<PieceResponse> myPortfolio() {
        return listOwn.execute(actors.currentActor()).stream().map(PieceResponse::of).toList();
    }

    @PostMapping("/me/portfolio/{pieceId}/hide")
    @Operation(summary = "Take a piece out of the public portfolio")
    @ApiResponse(responseCode = "200", description = "The piece is hidden")
    PieceResponse hide(@PathVariable UUID pieceId) {
        return PieceResponse.of(changeVisibility.hide(actors.currentActor(), pieceId));
    }

    @PostMapping("/me/portfolio/{pieceId}/show")
    @Operation(summary = "Put a hidden piece back in the public portfolio")
    @ApiResponse(responseCode = "200", description = "The piece is public again")
    PieceResponse show(@PathVariable UUID pieceId) {
        return PieceResponse.of(changeVisibility.show(actors.currentActor(), pieceId));
    }

    @GetMapping("/fixers/{fixerUserId}/portfolio")
    @Operation(summary = "Read the public portfolio of a fixer",
            description = "Returns only the pieces the fixer chose to show, in publication order. "
                    + "Each piece still carries its storage key, which exposes how the bucket is "
                    + "organised: before production this becomes a controlled read URL or a media "
                    + "identifier, an incompatible change of this contract.")
    @ApiResponse(responseCode = "200", description = "The public portfolio of that fixer")
    List<PieceResponse> portfolioOf(@PathVariable UUID fixerUserId) {
        return publicPortfolio.execute(fixerUserId).stream().map(PieceResponse::of).toList();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record PieceRequest(@NotNull PortfolioPieceKind kind,
            @NotBlank @Size(max = 512) String storageKey,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 1000) String description) {
    }

    /**
     * Temporary shape: {@code storageKey} keeps the current client working, but it exposes how the
     * bucket is organised. It must become a controlled read URL or a media identifier before
     * production, which is an incompatible contract change.
     */
    @Schema(requiredProperties = {"id", "kind", "storageKey", "title", "position", "visibility"})
    record PieceResponse(UUID id, PortfolioPieceKind kind, String storageKey, String title,
            @Schema(types = {"string", "null"}) String description,
            int position, PortfolioVisibility visibility, Instant createdAt) {

        static PieceResponse of(PortfolioPiece piece) {
            return new PieceResponse(piece.id(), piece.kind(), piece.storageKey(), piece.title(),
                    piece.description(), piece.position(), piece.visibility(), piece.createdAt());
        }
    }
}
