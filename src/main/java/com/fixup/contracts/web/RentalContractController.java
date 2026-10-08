package com.fixup.contracts.web;

import com.fixup.contracts.application.ContractResponse;
import com.fixup.contracts.application.CreateContract;
import com.fixup.contracts.application.CreateContractRequest;
import com.fixup.contracts.application.GetContract;
import com.fixup.contracts.application.ListMyContracts;
import com.fixup.contracts.application.RenewContract;
import com.fixup.contracts.application.TerminateContract;
import com.fixup.contracts.application.UpdateContract;
import com.fixup.contracts.application.UpdateContractRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/contracts")
class RentalContractController {

    private final CreateContract createContract;
    private final ListMyContracts listMyContracts;
    private final GetContract getContract;
    private final UpdateContract updateContract;
    private final RenewContract renewContract;
    private final TerminateContract terminateContract;

    RentalContractController(CreateContract createContract, ListMyContracts listMyContracts,
                             GetContract getContract, UpdateContract updateContract,
                             RenewContract renewContract, TerminateContract terminateContract) {
        this.createContract = createContract;
        this.listMyContracts = listMyContracts;
        this.getContract = getContract;
        this.updateContract = updateContract;
        this.renewContract = renewContract;
        this.terminateContract = terminateContract;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContractResponse create(@RequestBody CreateContractRequest request) {
        return createContract.execute(request);
    }

    @GetMapping("/me")
    public List<ContractResponse> listMe() {
        return listMyContracts.execute();
    }

    @GetMapping("/{id}")
    public ContractResponse get(@PathVariable UUID id) {
        return getContract.execute(id);
    }

    @PutMapping("/{id}")
    public ContractResponse update(@PathVariable UUID id, @RequestBody UpdateContractRequest request) {
        return updateContract.execute(id, request);
    }

    @PostMapping("/{id}/renew")
    public ContractResponse renew(@PathVariable UUID id, @RequestBody Map<String, LocalDate> body) {
        LocalDate newEndDate = body.get("newEndDate");
        if (newEndDate == null) {
            throw new IllegalArgumentException("newEndDate is required");
        }
        return renewContract.execute(id, newEndDate);
    }

    @PostMapping("/{id}/terminate")
    public ContractResponse terminate(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        String reason = (body != null) ? body.getOrDefault("reason", "No reason provided") : "No reason provided";
        return terminateContract.execute(id, reason);
    }

    @GetMapping("/expiring")
    public List<ContractResponse> expiring(@RequestParam(defaultValue = "30") int days) {
        return listMyContracts.executeExpiring(days);
    }
}
