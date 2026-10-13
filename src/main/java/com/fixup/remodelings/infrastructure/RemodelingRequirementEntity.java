package com.fixup.remodelings.infrastructure;

import com.fixup.remodelings.domain.RemodelingRequirement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "remodeling_requirements")
class RemodelingRequirementEntity {
    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "position", nullable = false, updatable = false)
    private int position;

    protected RemodelingRequirementEntity() {
    }

    static RemodelingRequirementEntity from(RemodelingRequirement requirement) {
        var entity = new RemodelingRequirementEntity();
        entity.id = requirement.id();
        entity.projectId = requirement.projectId();
        entity.requestId = requirement.requestId();
        entity.position = requirement.position();
        return entity;
    }

    RemodelingRequirement toDomain() {
        return new RemodelingRequirement(id, projectId, requestId, position);
    }
}
