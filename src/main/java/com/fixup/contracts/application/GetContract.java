package com.fixup.contracts.application;

import com.fixup.contracts.domain.RentalContract;
import com.fixup.contracts.domain.RentalContracts;
import com.fixup.identityaccess.api.CurrentActorProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class GetContract {
    private final RentalContracts contracts;
    private final CurrentActorProvider currentActorProvider;

    public GetContract(RentalContracts contracts, CurrentActorProvider currentActorProvider) {
        this.contracts = contracts;
        this.currentActorProvider = currentActorProvider;
    }

    public ContractResponse execute(UUID id) {
        var actor = currentActorProvider.currentActor();
        RentalContract contract = ContractAccess.requireExists(contracts, id);
        contract.requireVisibleTo(actor);
        return ContractResponse.from(contract);
    }
}
