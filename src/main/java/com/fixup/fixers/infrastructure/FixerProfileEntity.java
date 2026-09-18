package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.domain.FixerProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fixer_profiles")
class FixerProfileEntity {
    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 32)
    private FixerVerificationStatus verificationStatus;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FixerProfileEntity() {
    }

    static FixerProfileEntity from(FixerProfile profile) {
        var entity = new FixerProfileEntity();
        entity.userId = profile.userId();
        entity.verificationStatus = profile.verificationStatus();
        entity.createdAt = profile.createdAt();
        entity.updatedAt = profile.updatedAt();
        return entity;
    }

    FixerProfile toDomain() {
        return new FixerProfile(userId, verificationStatus, createdAt, updatedAt);
    }
}
