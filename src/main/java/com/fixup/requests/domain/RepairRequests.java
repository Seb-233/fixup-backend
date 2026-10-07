package com.fixup.requests.domain;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RepairRequests {
    Optional<RepairRequest> findById(UUID id);

    /** Reads the request for a decision, holding the row until the transaction ends. */
    Optional<RepairRequest> findByIdForUpdate(UUID id);

    List<RepairRequest> findByOwner(UUID ownerUserId);

    /** Open requests offered to the fixers, newest first, optionally narrowed to one specialty. */
    List<RepairRequest> findOpen(Specialty specialty);

    /** Open requests offered to fixers matching any of the given specialties, newest first. */
    List<RepairRequest> findOpenBySpecialties(Collection<Specialty> specialties);

    /** Open requests matching specialties and property city (proximity filter). */
    List<RepairRequest> findOpenBySpecialtiesAndCity(Collection<Specialty> specialties, String city);

    /** Urgent requests not yet resolved (OPEN or ASSIGNED or SLA_WARNING) with non-null slaDeadline. */
    List<RepairRequest> findUrgentUnresolved();

    /** Paginated requests for the SLA board: SLA_WARNING or SLA_BREACHED. */
    Page<RepairRequest> findSlaBoardRequests(Pageable pageable);

    /** Updates the status of a request directly by ID. */
    void updateStatusById(UUID id, RepairRequestStatus status, java.time.Instant now);

    void create(RepairRequest request);

    void update(RepairRequest request);
}
