package com.fixup.remodelings.domain;

import com.fixup.remodelings.api.RemodelingConflictException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RemodelingProject(UUID id, UUID propertyId, UUID ownerUserId, String title,
        String description, List<RemodelingRequirement> requirements, Instant createdAt,
        Instant updatedAt, RemodelingPlan plan) {

    public static final int MAX_TITLE_LENGTH = 150;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;

    public RemodelingProject {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(propertyId, "propertyId must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        title = normalizeTitle(title);
        description = normalizeDescription(description);
        if (requirements == null || requirements.isEmpty()) {
            throw new RemodelingConflictException("REQUIREMENTS_REQUIRED",
                    "A remodeling project requires at least one requirement");
        }
        requirements = requirements.stream()
                .sorted(Comparator.comparingInt(RemodelingRequirement::position))
                .toList();
        validateRequirements(id, requirements);
        plan = plan == null ? RemodelingPlan.IMPROVEMENT : plan;
    }

    public RemodelingProject(UUID id, UUID propertyId, UUID ownerUserId, String title, String description,
            List<RemodelingRequirement> requirements, Instant createdAt, Instant updatedAt) {
        this(id, propertyId, ownerUserId, title, description, requirements, createdAt, updatedAt,
                RemodelingPlan.IMPROVEMENT);
    }

    public static RemodelingProject create(UUID id, UUID propertyId, UUID ownerUserId,
            String title, String description, List<RemodelingRequirement> requirements, Instant now) {
        return create(id, propertyId, ownerUserId, title, description, requirements, RemodelingPlan.IMPROVEMENT, now);
    }

    public static RemodelingProject create(UUID id, UUID propertyId, UUID ownerUserId, String title,
            String description, List<RemodelingRequirement> requirements, RemodelingPlan plan, Instant now) {
        return new RemodelingProject(id, propertyId, ownerUserId, title, description, requirements, now, now, plan);
    }

    /** FR-UC-26: publicar en arriendo es un hecho único del proyecto pre-arriendo terminado. */
    public RemodelingProject markListingPublished(Instant now) {
        if (plan.goal() != com.fixup.remodelings.api.RemodelingGoal.PRE_RENTAL) {
            throw new RemodelingConflictException("NOT_PRE_RENTAL", "Only a pre-rental remodeling publishes a listing");
        }
        if (plan.listingPublishedAt() != null) {
            throw new RemodelingConflictException("LISTING_ALREADY_PUBLISHED", "The listing was already published");
        }
        return new RemodelingProject(id, propertyId, ownerUserId, title, description, requirements, createdAt, now,
                plan.published(now));
    }

    private static String normalizeTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new RemodelingConflictException("INVALID_TITLE", "The project title is required");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new RemodelingConflictException("INVALID_TITLE", "The project title is too long");
        }
        return normalized;
    }

    private static String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_DESCRIPTION_LENGTH) {
            throw new RemodelingConflictException("INVALID_DESCRIPTION",
                    "The project description is too long");
        }
        return normalized;
    }

    private static void validateRequirements(UUID projectId, List<RemodelingRequirement> requirements) {
        var ids = new HashSet<UUID>();
        var requestIds = new HashSet<UUID>();
        var positions = new HashSet<Integer>();
        for (var requirement : requirements) {
            if (!projectId.equals(requirement.projectId())) {
                throw new RemodelingConflictException("INVALID_REQUIREMENT_PROJECT",
                        "Every requirement must belong to its remodeling project");
            }
            if (!ids.add(requirement.id()) || !requestIds.add(requirement.requestId())
                    || !positions.add(requirement.position())) {
                throw new RemodelingConflictException("DUPLICATE_REQUIREMENT",
                        "Requirement identifiers, request identifiers and positions must be unique");
            }
        }
        for (int position = 0; position < requirements.size(); position++) {
            if (!positions.contains(position)) {
                throw new RemodelingConflictException("INVALID_REQUIREMENT_ORDER",
                        "Requirement positions must be contiguous and start at zero");
            }
        }
    }
}
