package com.fixup.payments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerDebts {
    boolean hasOutstandingDebt(UUID ownerUserId);
    Optional<OwnerDebt> findById(UUID id);
    List<OwnerDebt> findByOwner(UUID ownerUserId);
    void create(OwnerDebt debt);
    void update(OwnerDebt debt);
}
