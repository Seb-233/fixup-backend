package com.fixup.requests.domain;

import com.fixup.requests.api.Specialty;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepairRequests {
    Optional<RepairRequest> findById(UUID id);

    /** Reads the request for a decision, holding the row until the transaction ends. */
    Optional<RepairRequest> findByIdForUpdate(UUID id);

    List<RepairRequest> findByOwner(UUID ownerUserId);

    /** Open requests offered to the fixers, newest first, optionally narrowed to one specialty. */
    List<RepairRequest> findOpen(Specialty specialty);

    /** Open requests offered to fixers matching any of the given specialties, newest first. */
    List<RepairRequest> findOpenBySpecialties(java.util.Collection<Specialty> specialties);

    void create(RepairRequest request);

    void update(RepairRequest request);
}
