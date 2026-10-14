package com.fixup.contracts.application;

import com.fixup.identityaccess.api.UserDirectory;
import com.fixup.properties.api.PropertyDirectory;
import com.fixup.properties.api.PropertySnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-UC-14: un contrato se lee con el inmueble y las partes nombradas, no como tres identificadores. */
@Service
public class ContractContext {
    private final PropertyDirectory properties;
    private final UserDirectory users;

    ContractContext(PropertyDirectory properties, UserDirectory users) {
        this.properties = properties;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<ContractResponse> decorate(List<ContractResponse> contracts) {
        if (contracts.isEmpty()) {
            return contracts;
        }
        var propertyById = new HashMap<UUID, PropertySnapshot>();
        properties.findAll(contracts.stream().map(ContractResponse::propertyId).distinct().toList())
                .forEach(property -> propertyById.put(property.id(), property));
        var people = new ArrayList<UUID>();
        contracts.forEach(contract -> {
            people.add(contract.ownerUserId());
            people.add(contract.tenantUserId());
        });
        var names = users.displayNames(people);
        return contracts.stream().map(contract -> {
            var property = propertyById.get(contract.propertyId());
            return contract.withContext(property == null ? null : property.name(),
                    property == null ? null : property.address(), property == null ? null : property.city(),
                    names.get(contract.ownerUserId()), names.get(contract.tenantUserId()));
        }).toList();
    }

    public ContractResponse decorate(ContractResponse contract) {
        return decorate(List.of(contract)).get(0);
    }
}
