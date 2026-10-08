package com.fixup.contracts.infrastructure;

import com.fixup.contracts.api.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
interface RentalContractJpaRepository extends JpaRepository<RentalContractEntity, UUID> {

    @Query("SELECT c.id FROM RentalContractEntity c WHERE c.propertyId = :propertyId " +
           "AND c.status IN ('ACTIVE', 'RENEWED') AND c.startDate <= :end AND c.endDate >= :start")
    List<UUID> findOverlappingActiveContractIds(
        @Param("propertyId") UUID propertyId,
        @Param("start") LocalDate start,
        @Param("end") LocalDate end
    );

    List<RentalContractEntity> findByOwnerUserIdOrTenantUserIdOrderByCreatedAtDesc(
        UUID ownerUserId, UUID tenantUserId
    );

    @Query("SELECT c FROM RentalContractEntity c WHERE c.status IN ('ACTIVE', 'RENEWED') " +
           "AND c.endDate >= :today AND c.endDate <= :limit")
    List<RentalContractEntity> findActiveExpiringBetween(
        @Param("today") LocalDate today,
        @Param("limit") LocalDate limit
    );

    @Query("SELECT c FROM RentalContractEntity c WHERE c.status IN ('ACTIVE', 'RENEWED') " +
           "AND c.endDate >= :from AND c.endDate <= :to")
    List<RentalContractEntity> findActiveEndingBetween(
        @Param("from") LocalDate from,
        @Param("to") LocalDate to
    );
}
