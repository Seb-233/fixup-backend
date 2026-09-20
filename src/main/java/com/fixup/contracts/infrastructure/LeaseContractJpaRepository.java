package com.fixup.contracts.infrastructure;

import com.fixup.contracts.api.ContractStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LeaseContractJpaRepository extends JpaRepository<LeaseContractEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LeaseContractEntity c WHERE c.id = :id")
    Optional<LeaseContractEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT c FROM LeaseContractEntity c WHERE ("
            + "(:role = 'OWNER' AND c.ownerUserId = :userId) "
            + "OR (:role = 'TENANT' AND c.tenantUserId = :userId) "
            + "OR (:role = 'MANAGER' AND c.realEstateManagerUserId = :userId) "
            + "OR (:role = 'ANY' AND (c.ownerUserId = :userId OR c.tenantUserId = :userId "
            + "    OR c.realEstateManagerUserId = :userId))) "
            + "AND (:status IS NULL OR c.status = :status) "
            + "AND (:propertyId IS NULL OR c.propertyId = :propertyId) "
            + "AND (:startFrom IS NULL OR c.startDate >= :startFrom) "
            + "AND (:endUntil IS NULL OR c.endDate <= :endUntil) "
            + "ORDER BY c.updatedAt DESC")
    List<LeaseContractEntity> findByUserFiltered(@Param("userId") UUID userId,
            @Param("role") String role,
            @Param("status") ContractStatus status,
            @Param("propertyId") UUID propertyId,
            @Param("startFrom") LocalDate startFrom,
            @Param("endUntil") LocalDate endUntil,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT c FROM LeaseContractEntity c WHERE c.propertyId = :propertyId "
            + "AND (:status IS NULL OR c.status = :status) "
            + "ORDER BY c.createdAt DESC")
    List<LeaseContractEntity> findByPropertyFiltered(@Param("propertyId") UUID propertyId,
            @Param("status") ContractStatus status,
            org.springframework.data.domain.Pageable pageable);
}
