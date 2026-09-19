package com.fixup.media.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.media.api.MediaPurpose;
import com.fixup.media.application.ConfirmUpload;
import com.fixup.media.application.RequestUploadTicket;
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
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/media/uploads", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid payload",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account, or fixer not verified",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Media not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Upload expired, already attached, or object not ready",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "413", description = "Media file too large",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "415", description = "Media type not allowed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class MediaUploadsController {
    private final CurrentActorProvider actors;
    private final RequestUploadTicket requestUpload;
    private final ConfirmUpload confirmUpload;

    MediaUploadsController(
            CurrentActorProvider actors,
            RequestUploadTicket requestUpload,
            ConfirmUpload confirmUpload) {
        this.actors = actors;
        this.requestUpload = requestUpload;
        this.confirmUpload = confirmUpload;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Request a presigned upload URL",
            description = "Creates a pending media asset ticket and issues a signed PUT URL.")
    @ApiResponse(responseCode = "201", description = "Upload ticket created")
    UploadTicketDto requestUpload(@Valid @RequestBody UploadRequestDto request) {
        var response = requestUpload.execute(
                actors.currentActor(),
                new RequestUploadTicket.NewUploadRequest(request.purpose(), request.contentType(), request.sizeBytes()));
        return new UploadTicketDto(
                response.mediaId(),
                response.method(),
                response.uploadUrl(),
                response.headers(),
                response.expiresAt());
    }

    @PostMapping("/{mediaId}/confirm")
    @Operation(summary = "Confirm an uploaded media object",
            description = "Verifies the object existence, size and content signature against storage. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Media confirmed and ready")
    ConfirmResponseDto confirmUpload(@PathVariable UUID mediaId) {
        var response = confirmUpload.execute(actors.currentActor(), mediaId);
        return new ConfirmResponseDto(response.mediaId(), response.status());
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record UploadRequestDto(
            @NotNull MediaPurpose purpose,
            @NotBlank String contentType,
            @NotNull @Positive Long sizeBytes) {
    }

    @Schema(requiredProperties = {"mediaId", "method", "uploadUrl", "headers", "expiresAt"})
    record UploadTicketDto(
            UUID mediaId,
            String method,
            String uploadUrl,
            Map<String, String> headers,
            Instant expiresAt) {
    }

    @Schema(requiredProperties = {"mediaId", "status"})
    record ConfirmResponseDto(UUID mediaId, String status) {
    }
}
