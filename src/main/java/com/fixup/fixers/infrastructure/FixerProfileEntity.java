package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerProfile;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "fixer_specialties", joinColumns = @JoinColumn(name = "fixer_user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "specialty", nullable = false, length = 50)
    private Set<Specialty> specialties = new HashSet<>();
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FixerProfileEntity() {
    }

    static FixerProfileEntity from(FixerProfile profile) {
        var entity = new FixerProfileEntity();
        entity.userId = profile.userId();
        entity.apply(profile);
        entity.createdAt = profile.createdAt();
        return entity;
    }

    void apply(FixerProfile profile) {
        verificationStatus = profile.verificationStatus();
        specialties = new HashSet<>(profile.specialties());
        submittedAt = profile.submittedAt();
        decidedAt = profile.decidedAt();
        decidedBy = profile.decidedBy();
        rejectionReason = profile.rejectionReason();
        updatedAt = profile.updatedAt();
    }

    FixerProfile toDomain() {
        return new FixerProfile(userId, verificationStatus, Set.copyOf(specialties), submittedAt, decidedAt, decidedBy, rejectionReason,
                createdAt, updatedAt);
    }
}
