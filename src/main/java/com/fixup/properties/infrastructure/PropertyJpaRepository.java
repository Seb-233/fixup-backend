package com.fixup.properties.infrastructure;

import com.fixup.properties.api.PropertyStatus;
import com.fixup.properties.api.PropertyType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PropertyJpaRepository extends JpaRepository<PropertyEntity, UUID> {

    List<PropertyEntity> findByIdIn(Collection<UUID> ids);

    @Query("""
        SELECT p FROM PropertyEntity p
        WHERE p.status <> 'DELETED'
          AND (:ownerId IS NULL OR p.ownerUserId = :ownerId OR :managerId IS NOT NULL AND p.managerUserId = :managerId)
          AND (:status IS NULL OR p.status = :status)
          AND (:managerFilter IS NULL OR p.managerUserId = :managerFilter)
        ORDER BY p.updatedAt DESC
        """)
    List<PropertyEntity> findByOwnerOrManager(
            @Param("ownerId") UUID ownerId,
            @Param("managerId") UUID managerId,
            @Param("status") PropertyStatus status,
            @Param("managerFilter") UUID managerFilter,
            Pageable limit);

    @Query("""
        SELECT p FROM PropertyEntity p
        WHERE p.status = 'PUBLISHED'
          AND (:type IS NULL OR p.type = :type)
          AND (:city IS NULL OR LOWER(p.city) = LOWER(CAST(:city AS string)))
          AND (:zone IS NULL OR LOWER(p.zone) = LOWER(CAST(:zone AS string)))
          AND (:minRent IS NULL OR p.monthlyRentSuggestion >= :minRent)
          AND (:maxRent IS NULL OR p.monthlyRentSuggestion <= :maxRent)
          AND (:minBedrooms IS NULL OR p.bedrooms >= :minBedrooms)
          AND (:minBathrooms IS NULL OR p.bathrooms >= :minBathrooms)
          AND (:minSurface IS NULL OR p.surfaceM2 >= :minSurface)
        ORDER BY p.publishedAt DESC
        """)
    List<PropertyEntity> findPublishedWithFilters(
            @Param("type") PropertyType type,
            @Param("city") String city,
            @Param("zone") String zone,
            @Param("minRent") BigDecimal minRent,
            @Param("maxRent") BigDecimal maxRent,
            @Param("minBedrooms") Integer minBedrooms,
            @Param("minBathrooms") Integer minBathrooms,
            @Param("minSurface") Double minSurface,
            Pageable page);
}
