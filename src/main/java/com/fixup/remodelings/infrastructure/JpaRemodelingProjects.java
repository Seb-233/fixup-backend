package com.fixup.remodelings.infrastructure;

import com.fixup.remodelings.domain.RemodelingProject;
import com.fixup.remodelings.domain.RemodelingProjects;
import com.fixup.remodelings.domain.RemodelingRequirement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
class JpaRemodelingProjects implements RemodelingProjects {
    private final RemodelingProjectJpaRepository projects;
    private final RemodelingRequirementJpaRepository requirements;

    JpaRemodelingProjects(RemodelingProjectJpaRepository projects,
            RemodelingRequirementJpaRepository requirements) {
        this.projects = projects;
        this.requirements = requirements;
    }

    @Override
    public void create(RemodelingProject project) {
        projects.saveAndFlush(RemodelingProjectEntity.from(project));
        requirements.saveAllAndFlush(project.requirements().stream()
                .map(RemodelingRequirementEntity::from)
                .toList());
    }

    @Override
    public void updatePlan(RemodelingProject project) {
        var entity = projects.findById(project.id())
                .orElseThrow(() -> new IllegalStateException("The remodeling project disappeared"));
        entity.applyPlan(project);
        projects.saveAndFlush(entity);
    }

    @Override
    public Optional<RemodelingProject> findById(UUID id) {
        return projects.findById(id).map(entity -> toDomain(entity,
                requirements.findByProjectIdOrderByPosition(entity.id()).stream()
                        .map(RemodelingRequirementEntity::toDomain)
                        .toList()));
    }

    @Override
    public List<RemodelingProject> findByOwner(UUID ownerUserId) {
        var entities = projects.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<RemodelingRequirement>> byProject = requirements
                .findByProjectIdInOrderByProjectIdAscPositionAsc(idsOf(entities)).stream()
                .map(RemodelingRequirementEntity::toDomain)
                .collect(Collectors.groupingBy(RemodelingRequirement::projectId));
        return entities.stream()
                .map(entity -> toDomain(entity, byProject.getOrDefault(entity.id(), List.of())))
                .toList();
    }

    private Collection<UUID> idsOf(List<RemodelingProjectEntity> entities) {
        return entities.stream().map(RemodelingProjectEntity::id).toList();
    }

    private RemodelingProject toDomain(RemodelingProjectEntity entity,
            List<RemodelingRequirement> projectRequirements) {
        return new RemodelingProject(entity.id(), entity.propertyId(), entity.ownerUserId(),
                entity.title(), entity.description(), projectRequirements, entity.createdAt(),
                entity.updatedAt(), entity.plan());
    }
}
