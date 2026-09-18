package com.fixup.identityaccess.web;

import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.application.BootstrapUser;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Unexpected client fields or invalid initial profile",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Account suspended or disabled",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Identity conflict",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class AuthController {
    private final BootstrapUser bootstrap;

    AuthController(BootstrapUser bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostMapping("/bootstrap")
    @Operation(summary = "Provision the authenticated identity idempotently",
            description = "No body or an empty object. Subject and initial profile come only from the validated JWT.")
    @ApiResponse(responseCode = "200", description = "Existing active account",
            content = @Content(schema = @Schema(implementation = BootstrapResponse.class)))
    @ApiResponse(responseCode = "201", description = "New account without roles",
            content = @Content(schema = @Schema(implementation = BootstrapResponse.class)))
    ResponseEntity<BootstrapResponse> bootstrap(@RequestBody(required = false) BootstrapRequest ignored) {
        var result = bootstrap.execute();
        var user = result.user();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new BootstrapResponse(user.id(), user.externalSubject(), user.email(),
                        user.displayName(), user.status(), user.roles()));
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record BootstrapRequest() {
    }

    @Schema(requiredProperties = {"id", "externalSubject", "email", "displayName", "status", "roles"})
    record BootstrapResponse(UUID id, String externalSubject, @Schema(types = {"string", "null"}) String email, @Schema(types = {"string", "null"}) String displayName,
            UserStatus status, Set<Role> roles) {
    }
}
