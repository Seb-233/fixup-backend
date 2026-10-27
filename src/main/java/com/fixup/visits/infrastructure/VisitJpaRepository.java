package com.fixup.visits.infrastructure;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VisitJpaRepository extends JpaRepository<VisitEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select visit from VisitEntity visit where visit.id = :id")
    Optional<VisitEntity> findAndLockById(@Param("id") UUID id);

    List<VisitEntity> findByRequestIdOrderByScheduledAtAsc(UUID requestId);

    @Query("""
            select visit from VisitEntity visit
            where visit.ownerUserId = :userId or visit.requestedByUserId = :userId
               or visit.managerUserId = :userId or visit.fixerUserId = :userId
            order by visit.scheduledAt desc
            """)
    List<VisitEntity> findByParticipant(@Param("userId") UUID userId);

    @Query("""
            select visit from VisitEntity visit
            where visit.fixerUserId = :fixerUserId
              and visit.status in (com.fixup.visits.api.VisitStatus.PROPOSED, com.fixup.visits.api.VisitStatus.CONFIRMED)
              and visit.scheduledAt < :to and visit.scheduledAt >= :from
            """)
    List<VisitEntity> findActiveForFixerBetween(@Param("fixerUserId") UUID fixerUserId, @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
            select visit from VisitEntity visit
            where visit.status = com.fixup.visits.api.VisitStatus.CONFIRMED
              and visit.reminderSentAt is null
              and visit.scheduledAt >= :from and visit.scheduledAt <= :until
            """)
    List<VisitEntity> findDueForReminder(@Param("from") Instant from, @Param("until") Instant until);
}
