package com.fixup.media.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.PortfolioVisibility;
import com.fixup.media.application.ChangePieceVisibility;
import com.fixup.media.application.GetPublicPortfolio;
import com.fixup.media.application.ListOwnPortfolio;
import com.fixup.media.application.NewPortfolioPiece;
import com.fixup.media.application.PortfolioPieceView;
import com.fixup.media.application.PortfolioViewResolver;
import com.fixup.media.application.PublishPortfolioPiece;
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
 * No internal storage keys are exposed.
 */
@RestController
@RequestMapping(value = "/media", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Missing or invalid field",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, or the fixer is not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Media, piece or portfolio not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "A publication or media rule rejected the operation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class PortfolioController {
    private final CurrentActorProvider actors;
    private final PublishPortfolioPiece publishPiece;
    private final ListOwnPortfolio listOwn;
    private final ChangePieceVisibility changeVisibility;
    private final GetPublicPortfolio publicPortfolio;
    private final PortfolioViewResolver resolver;

    PortfolioController(
            CurrentActorProvider actors,
            PublishPortfolioPiece publishPiece,
            ListOwnPortfolio listOwn,
            ChangePieceVisibility changeVisibility,
            GetPublicPortfolio publicPortfolio,
            PortfolioViewResolver resolver) {
        this.actors = actors;
        this.publishPiece = publishPiece;
        this.listOwn = listOwn;
        this.changeVisibility = changeVisibility;
        this.publicPortfolio = publicPortfolio;
        this.resolver = resolver;
    }

    @PostMapping({"/me/portfolio/pieces", "/me/portfolio"})
    @Operation(summary = "Publish a piece in the fixer's own portfolio",
            description = "Attaches a confirmed media asset to the portfolio. Requires a verified fixer.")
    @ApiResponse(responseCode = "201", description = "The piece was published")
    @ResponseStatus(HttpStatus.CREATED)
    PieceResponse publish(@Valid @RequestBody PieceRequest request) {
        var piece = publishPiece.execute(actors.currentActor(),
                new NewPortfolioPiece(request.mediaId(), request.title(), request.description()));
        return PieceResponse.of(resolver.toView(piece));
    }

    @GetMapping("/me/portfolio")
    @Operation(summary = "List the fixer's own portfolio, hidden pieces included")
    @ApiResponse(responseCode = "200", description = "Every piece of the current fixer, in publication order")
    List<PieceResponse> myPortfolio() {
        return listOwn.execute(actors.currentActor()).stream().map(PieceResponse::of).toList();
    }

    @PostMapping({"/me/portfolio/pieces/{pieceId}/hide", "/me/portfolio/{pieceId}/hide"})
    @Operation(summary = "Take a piece out of the public portfolio")
    @ApiResponse(responseCode = "200", description = "The piece is hidden")
    PieceResponse hide(@PathVariable UUID pieceId) {
        var piece = changeVisibility.hide(actors.currentActor(), pieceId);
        return PieceResponse.of(resolver.toView(piece));
    }

    @PostMapping({"/me/portfolio/pieces/{pieceId}/show", "/me/portfolio/{pieceId}/show"})
    @Operation(summary = "Put a hidden piece back in the public portfolio")
    @ApiResponse(responseCode = "200", description = "The piece is public again")
    PieceResponse show(@PathVariable UUID pieceId) {
        var piece = changeVisibility.show(actors.currentActor(), pieceId);
        return PieceResponse.of(resolver.toView(piece));
    }

    @GetMapping("/fixers/{fixerUserId}/portfolio")
    @Operation(summary = "Read the public portfolio of a fixer",
            description = "Returns only the pieces the fixer chose to show, in publication order with secure read URLs.")
    @ApiResponse(responseCode = "200", description = "The public portfolio of that fixer")
    List<PieceResponse> portfolioOf(@PathVariable UUID fixerUserId) {
        return publicPortfolio.execute(fixerUserId).stream().map(PieceResponse::of).toList();
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record PieceRequest(
            @NotNull UUID mediaId,
            @NotBlank @Size(max = 120) String title,
            @Size(max = 1000) String description) {
    }

    @Schema(requiredProperties = {"id", "mediaId", "title", "position", "visibility", "readUrl", "readUrlExpiresAt"})
    record PieceResponse(
            UUID id,
            UUID mediaId,
            String title,
            @Schema(types = {"string", "null"}) String description,
            int position,
            PortfolioVisibility visibility,
            String readUrl,
            Instant readUrlExpiresAt,
            Instant createdAt) {

        static PieceResponse of(PortfolioPieceView view) {
            return new PieceResponse(
                    view.id(),
                    view.mediaId(),
                    view.title(),
                    view.description(),
                    view.position(),
                    view.visibility(),
                    view.readUrl(),
                    view.readUrlExpiresAt(),
                    view.createdAt());
        }
    }
}
