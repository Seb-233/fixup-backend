package com.fixup.contracts.application;

import com.fixup.contracts.api.TenancyDirectory;
import com.fixup.contracts.domain.RentalContracts;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ContractTenancyDirectory implements TenancyDirectory {
    private final RentalContracts contracts;

    ContractTenancyDirectory(RentalContracts contracts) {
        this.contracts = contracts;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveTenant(UUID tenantUserId, UUID propertyId, LocalDate on) {
        return activePropertyIds(tenantUserId, on).contains(propertyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> activePropertyIds(UUID tenantUserId, LocalDate on) {
        if (tenantUserId == null) {
            return List.of();
        }
        return contracts.findActivePropertyIdsForTenant(tenantUserId, on);
    }
}
