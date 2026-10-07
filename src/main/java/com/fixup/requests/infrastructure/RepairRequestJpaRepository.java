package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RepairRequestJpaRepository extends JpaRepository<RepairRequestEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from RepairRequestEntity request where request.id = :id")
    Optional<RepairRequestEntity> findAndLockById(@Param("id") UUID id);

    List<RepairRequestEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    List<RepairRequestEntity> findByStatusOrderByCreatedAtDesc(RepairRequestStatus status);

    List<RepairRequestEntity> findByStatusAndSpecialtyOrderByCreatedAtDesc(RepairRequestStatus status,
            Specialty specialty);

    List<RepairRequestEntity> findByStatusAndSpecialtyInOrderByCreatedAtDesc(RepairRequestStatus status,
            java.util.Collection<Specialty> specialties);
}
