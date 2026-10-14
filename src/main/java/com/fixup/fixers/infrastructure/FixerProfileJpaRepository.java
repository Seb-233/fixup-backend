package com.fixup.fixers.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FixerProfileJpaRepository extends JpaRepository<FixerProfileEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select profile from FixerProfileEntity profile where profile.userId = :userId")
    Optional<FixerProfileEntity> findAndLockByUserId(@Param("userId") UUID userId);

    @Query("""
            select distinct profile from FixerProfileEntity profile
            where profile.verificationStatus = com.fixup.fixers.api.FixerVerificationStatus.VERIFIED
            """)
    List<FixerProfileEntity> findVerified();

    @Query("""
            select profile from FixerProfileEntity profile
            where profile.verificationStatus = com.fixup.fixers.api.FixerVerificationStatus.PENDING
              and profile.submittedAt is not null
            order by profile.submittedAt asc
            """)
    List<FixerProfileEntity> findUnderReview();
}
