package com.fixup.properties.infrastructure;

import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
interface PropertyJpaRepository extends JpaRepository<PropertyEntity, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<PropertyEntity> {
    List<PropertyEntity> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    @Query("select p from PropertyEntity p where p.ownerUserId = :userId or p.managerUserId = :userId order by p.createdAt desc")
    List<PropertyEntity> findControlledBy(@Param("userId") UUID userId);

    interface PropertyDistance {
        String getId();
        double getDistanceKm();
    }

    @Query(value = """
            WITH candidates AS (
                SELECT p.id,
                       2.0 * 6371.0088 * ASIN(SQRT(
                           LEAST(1.0, GREATEST(0.0,
                               POWER(SIN((
                                   RADIANS(CAST(p.latitude AS DOUBLE PRECISION))
                                   - RADIANS(CAST(:latitude AS DOUBLE PRECISION))
                               ) / 2.0), 2)
                               + COS(RADIANS(CAST(:latitude AS DOUBLE PRECISION)))
                               * COS(RADIANS(CAST(p.latitude AS DOUBLE PRECISION)))
                               * POWER(SIN((
                                   RADIANS(CAST(p.longitude AS DOUBLE PRECISION))
                                   - RADIANS(CAST(:longitude AS DOUBLE PRECISION))
                               ) / 2.0), 2)
                           ))
                       )) AS distance_km
                FROM properties p
                WHERE p.status = 'PUBLISHED'
                  AND p.latitude IS NOT NULL
                  AND p.longitude IS NOT NULL
                  AND (
                      :ownershipScope = 'ALL'
                      OR (:ownershipScope = 'MINE' AND p.owner_user_id = :currentUserId)
                      OR (:ownershipScope = 'OTHERS' AND p.owner_user_id <> :currentUserId)
                  )
                  AND p.latitude BETWEEN :minLatitude AND :maxLatitude
                  AND (
                      :allLongitudes = TRUE
                      OR (:crossesAntimeridian = FALSE
                          AND p.longitude BETWEEN :minLongitude AND :maxLongitude)
                      OR (:crossesAntimeridian = TRUE
                          AND (p.longitude >= :minLongitude OR p.longitude <= :maxLongitude))
                  )
            )
            SELECT CAST(id AS VARCHAR) AS id, distance_km AS "distanceKm"
            FROM candidates
            WHERE distance_km <= CAST(:radiusKm AS DOUBLE PRECISION)
            ORDER BY distance_km ASC, id ASC
            """, nativeQuery = true)
    List<PropertyDistance> searchPublishedIds(
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude,
            @Param("radiusKm") BigDecimal radiusKm,
            @Param("currentUserId") UUID currentUserId,
            @Param("ownershipScope") String ownershipScope,
            @Param("minLatitude") BigDecimal minLatitude,
            @Param("maxLatitude") BigDecimal maxLatitude,
            @Param("minLongitude") BigDecimal minLongitude,
            @Param("maxLongitude") BigDecimal maxLongitude,
            @Param("allLongitudes") boolean allLongitudes,
            @Param("crossesAntimeridian") boolean crossesAntimeridian);
}
