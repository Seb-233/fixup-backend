package com.fixup.contracts.web;

import com.fixup.identityaccess.api.CurrentActorProvider;
import com.fixup.contracts.api.ContractStatus;
import com.fixup.contracts.application.CancelLeaseContract;
import com.fixup.contracts.application.ContractSummary;
import com.fixup.contracts.application.CreateLeaseContract;
import com.fixup.contracts.application.GetLeaseContract;
import com.fixup.contracts.application.ListContractsForUser;
import com.fixup.contracts.application.RenewLeaseContract;
import com.fixup.contracts.application.SignLeaseContract;
import com.fixup.contracts.application.TerminateLeaseContract;
import com.fixup.shared.errors.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/contracts", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid fields, missing data or unexpected client fields",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "Inactive account or not authorized on this contract",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "Contract not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "409", description = "Contract state does not allow the requested transition or invalid data",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
class LeaseContractController {
    private final CurrentActorProvider actors;
    private final CreateLeaseContract createLeaseContract;
    private final GetLeaseContract getLeaseContract;
    private final ListContractsForUser listContractsForUser;
    private final SignLeaseContract signLeaseContract;
    private final TerminateLeaseContract terminateLeaseContract;
    private final CancelLeaseContract cancelLeaseContract;
    private final RenewLeaseContract renewLeaseContract;

    LeaseContractController(CurrentActorProvider actors,
            CreateLeaseContract createLeaseContract, GetLeaseContract getLeaseContract,
            ListContractsForUser listContractsForUser, SignLeaseContract signLeaseContract,
            TerminateLeaseContract terminateLeaseContract, CancelLeaseContract cancelLeaseContract,
            RenewLeaseContract renewLeaseContract) {
        this.actors = actors;
        this.createLeaseContract = createLeaseContract;
        this.getLeaseContract = getLeaseContract;
        this.listContractsForUser = listContractsForUser;
        this.signLeaseContract = signLeaseContract;
        this.terminateLeaseContract = terminateLeaseContract;
        this.cancelLeaseContract = cancelLeaseContract;
        this.renewLeaseContract = renewLeaseContract;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a new lease contract (DRAFT status)")
    @ApiResponse(responseCode = "201", description = "Contract created in DRAFT status")
    @ResponseStatus(HttpStatus.CREATED)
    ContractSummary create(@Valid @RequestBody CreateLeaseContract.NewContractData body) {
        return createLeaseContract.execute(actors.currentActor(), body);
    }

    @GetMapping("/me")
    @Operation(summary = "List contracts visible to the current user",
            description = "Returns contracts where the user is the owner, tenant or real estate manager. "
                    + "Filter by role (OWNER/TENANT/MANAGER/ANY), status, property, date range.")
    @ApiResponse(responseCode = "200", description = "Contracts visible to the current user")
    List<ContractSummary> listMine(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) LocalDate startFrom,
            @RequestParam(required = false) LocalDate endUntil,
            @RequestParam(defaultValue = "100") int limit) {
        return listContractsForUser.execute(actors.currentActor(), role, status, propertyId,
                startFrom, endUntil, limit);
    }

    @GetMapping("/{contractId}")
    @Operation(summary = "Get one lease contract by ID")
    @ApiResponse(responseCode = "200", description = "The contract detail")
    ContractSummary detail(@PathVariable UUID contractId) {
        return getLeaseContract.execute(actors.currentActor(), contractId);
    }

    @PatchMapping("/{contractId}/send-for-tenant-signature")
    @Operation(summary = "Owner sends the contract to tenant for first signature",
            description = "Transition DRAFT -> PENDING_TENANT_SIGNATURE. "
                    + "Can only be called by owner, manager or platform admin.")
    ContractSummary sendForTenantSignature(@PathVariable UUID contractId) {
        return signLeaseContract.sendForTenantSignature(actors.currentActor(), contractId);
    }

    @PatchMapping("/{contractId}/sign-as-tenant")
    @Operation(summary = "Tenant signs the contract",
            description = "Transition PENDING_TENANT_SIGNATURE -> PENDING_OWNER_SIGNATURE.")
    ContractSummary signAsTenant(@PathVariable UUID contractId) {
        return signLeaseContract.asTenant(actors.currentActor(), contractId);
    }

    @PatchMapping("/{contractId}/sign-as-owner")
    @Operation(summary = "Owner/manager signs the contract",
            description = "Transition PENDING_OWNER_SIGNATURE -> SIGNED or ACTIVE (if already started).")
    ContractSummary signAsOwner(@PathVariable UUID contractId) {
        return signLeaseContract.asOwner(actors.currentActor(), contractId);
    }

    @PatchMapping(value = "/{contractId}/terminate-as-owner", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Owner terminates a SIGNED or ACTIVE contract")
    ContractSummary terminateAsOwner(@PathVariable UUID contractId,
            @Valid @RequestBody TerminateCancelRequest body) {
        return terminateLeaseContract.asOwner(actors.currentActor(), contractId, body.reason());
    }

    @PatchMapping(value = "/{contractId}/terminate-as-tenant", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Tenant terminates a SIGNED or ACTIVE contract")
    ContractSummary terminateAsTenant(@PathVariable UUID contractId,
            @Valid @RequestBody TerminateCancelRequest body) {
        return terminateLeaseContract.asTenant(actors.currentActor(), contractId, body.reason());
    }

    @PatchMapping(value = "/{contractId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a non-final contract",
            description = "Can be used by owner, manager, tenant or admin to cancel any non-final contract.")
    ContractSummary cancel(@PathVariable UUID contractId,
            @Valid @RequestBody TerminateCancelRequest body) {
        return cancelLeaseContract.execute(actors.currentActor(), contractId, body.reason());
    }

    @PostMapping(value = "/{contractId}/renew", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Renew an ACTIVE/SIGNED/EXPIRED contract",
            description = "Creates a NEW contract referencing the original via originalContractId. "
                    + "Owner or manager only. The new contract starts in DRAFT again.")
    @ApiResponse(responseCode = "201", description = "Renewed contract created")
    @ResponseStatus(HttpStatus.CREATED)
    ContractSummary renew(@PathVariable UUID contractId,
            @Valid @RequestBody RenewLeaseContract.RenewalData body) {
        return renewLeaseContract.execute(actors.currentActor(), contractId, body);
    }

    record TerminateCancelRequest(@NotNull @Size(max = 2000) String reason) {
    }
}
