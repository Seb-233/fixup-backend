package com.fixup.properties.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PropertyWorkRecordJpaRepository extends JpaRepository<PropertyWorkRecordEntity, UUID> {
    boolean existsByJobId(UUID jobId);

    List<PropertyWorkRecordEntity> findByPropertyIdOrderByCompletedAtDesc(UUID propertyId);

    @Modifying
    @Query("update PropertyWorkRecordEntity w set w.rating = :rating where w.jobId = :jobId")
    int updateRating(@Param("jobId") UUID jobId, @Param("rating") int rating);

    interface SealRow {
        UUID getPropertyId();
        long getRecentWorks();
        Double getAverageRating();
        Instant getLastWorkAt();
    }

    @Query("""
            select w.propertyId as propertyId, count(w) as recentWorks, avg(w.rating) as averageRating,
                   max(w.completedAt) as lastWorkAt
            from PropertyWorkRecordEntity w
            where w.propertyId in :propertyIds and w.completedAt >= :since
            group by w.propertyId
            """)
    List<SealRow> sealRows(@Param("propertyIds") Collection<UUID> propertyIds, @Param("since") Instant since);
}
