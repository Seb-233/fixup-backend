package com.fixup.requests.infrastructure;

import com.fixup.requests.api.RepairRequestStatus;
import com.fixup.fixers.api.Specialty;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
            Collection<Specialty> specialties);

    List<RepairRequestEntity> findByStatusAndSpecialtyInAndPropertyCityOrderByCreatedAtDesc(
            RepairRequestStatus status, Collection<Specialty> specialties, String propertyCity);

    @Query("select request from RepairRequestEntity request where request.status in (:statuses) and request.slaDeadline is not null order by request.slaDeadline asc")
    List<RepairRequestEntity> findByStatusInAndSlaDeadlineNotNullOrderBySlaDeadlineAsc(
            @Param("statuses") Collection<RepairRequestStatus> statuses);

    @Query("select request from RepairRequestEntity request where request.status in (:statuses) order by request.slaDeadline asc, request.createdAt desc")
    Page<RepairRequestEntity> findByStatusInOrderBySlaDeadlineAscCreatedAtDesc(
            @Param("statuses") Collection<RepairRequestStatus> statuses, Pageable pageable);

    @Modifying
    @Query("update RepairRequestEntity request set request.status = :status, request.updatedAt = :now where request.id = :id")
    int updateStatusById(@Param("id") UUID id, @Param("status") RepairRequestStatus status,
            @Param("now") java.time.Instant now);
}
