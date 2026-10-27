package com.fixup.fixers.infrastructure;

import com.fixup.fixers.api.FixerVerificationStatus;
import com.fixup.fixers.api.Specialty;
import com.fixup.fixers.domain.FixerCard;
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
import java.math.BigDecimal;
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
    @Column(name = "consent_accepted_at")
    private Instant consentAcceptedAt;
    @Column(name = "consent_version", length = 16)
    private String consentVersion;
    @Column(name = "city", length = 100)
    private String city;
    @Column(name = "latitude", precision = 10, scale = 6)
    private BigDecimal latitude;
    @Column(name = "longitude", precision = 10, scale = 6)
    private BigDecimal longitude;
    @Column(name = "bio", length = 600)
    private String bio;
    @Column(name = "years_experience")
    private Integer yearsExperience;
    @Column(name = "rating_average", precision = 3, scale = 2)
    private BigDecimal ratingAverage;
    @Column(name = "rating_count", nullable = false)
    private int ratingCount;
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
        consentAcceptedAt = profile.consentAcceptedAt();
        consentVersion = profile.consentVersion();
        city = profile.city();
        latitude = profile.latitude();
        longitude = profile.longitude();
        updatedAt = profile.updatedAt();
    }

    FixerCard toCard() {
        return new FixerCard(toDomain(), bio, yearsExperience, ratingAverage, ratingCount);
    }

    void present(String city, String bio, Integer yearsExperience, Instant now) {
        this.city = city;
        this.bio = bio;
        this.yearsExperience = yearsExperience;
        this.updatedAt = now;
    }

    void rate(BigDecimal ratingAverage, int ratingCount, Instant now) {
        this.ratingAverage = ratingAverage;
        this.ratingCount = ratingCount;
        this.updatedAt = now;
    }

    UUID userId() {
        return userId;
    }

    FixerProfile toDomain() {
        return new FixerProfile(userId, verificationStatus, Set.copyOf(specialties), submittedAt, decidedAt, decidedBy,
                rejectionReason, consentAcceptedAt, consentVersion, city, latitude, longitude, createdAt, updatedAt);
    }
}
