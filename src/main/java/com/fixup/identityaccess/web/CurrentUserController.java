package com.fixup.identityaccess.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.identityaccess.api.Role;
import com.fixup.identityaccess.api.UserStatus;
import com.fixup.identityaccess.application.GetCurrentUser;
import com.fixup.identityaccess.application.SelectInitialRole;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid role, missing role or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or forbidden role assignment",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Bootstrap is required before accessing the internal account",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class CurrentUserController {
    private final CurrentActorProvider actors;
    private final GetCurrentUser getUser;
    private final SelectInitialRole selectRole;

    CurrentUserController(CurrentActorProvider actors, GetCurrentUser getUser, SelectInitialRole selectRole) {
        this.actors = actors;
        this.getUser = getUser;
        this.selectRole = selectRole;
    }

    @GetMapping("/me")
    @Operation(summary = "Read the current internal account")
    @ApiResponse(responseCode = "200", description = "Current active account")
    UserResponse me() {
        var user = getUser.execute(actors.currentActor());
        return new UserResponse(user.id(), user.email(), user.displayName(), user.status(), user.roles());
    }

    @PostMapping("/select-role")
    @Operation(summary = "Request an initial role idempotently",
            description = "Only OWNER, TENANT and FIXER are self-assignable. FIXER starts PENDING. "
                    + "Administrative roles require a dedicated administrative use case.")
    @ApiResponse(responseCode = "200", description = "The current user's internal roles")
    RolesResponse selectRole(@Valid @RequestBody RoleRequest request) {
        return new RolesResponse(selectRole.execute(actors.currentActor(), request.role()));
    }

    @Schema(requiredProperties = {"id", "email", "displayName", "status", "roles"})
    record UserResponse(UUID id, @Schema(types = {"string", "null"}) String email, @Schema(types = {"string", "null"}) String displayName, UserStatus status, Set<Role> roles) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record RoleRequest(@NotNull Role role) {
    }

    @Schema(requiredProperties = {"roles"})
    record RolesResponse(Set<Role> roles) {
    }
}
