package com.fixup.contracts.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.contracts.api.ContractNotFoundException;
import com.fixup.contracts.domain.Contracts;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetLeaseContract {
    private final Contracts contracts;

    GetLeaseContract(Contracts contracts) {
        this.contracts = contracts;
    }

    @Transactional(readOnly = true)
    public ContractSummary execute(CurrentActor actor, UUID contractId) {
        ContractAccess.requireActiveUser(actor);
        var c = contracts.findById(contractId).orElseThrow(ContractNotFoundException::new);
        ContractAccess.requireVisibleBy(actor, c);
        return ContractSummary.of(c);
    }
}
