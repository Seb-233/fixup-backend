package com.fixup.remodelings;

import com.fixup.remodelings.api.RemodelingConflictException;
import com.fixup.remodelings.domain.RemodelingProject;
import com.fixup.remodelings.domain.RemodelingRequirement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemodelingProjectTest {
    @Test
    void acceptsOneRequirementAndNormalizesText() {
        UUID projectId = UUID.randomUUID();
        var requirement = new RemodelingRequirement(UUID.randomUUID(), projectId,
                UUID.randomUUID(), 0);

        var project = RemodelingProject.create(projectId, UUID.randomUUID(), UUID.randomUUID(),
                "  Cocina nueva  ", "  Cambio integral  ", List.of(requirement), Instant.now());

        assertThat(project.title()).isEqualTo("Cocina nueva");
        assertThat(project.description()).isEqualTo("Cambio integral");
        assertThat(project.requirements()).containsExactly(requirement);
    }

    @Test
    void sortsSeveralRequirementsByTheirDeterministicPosition() {
        UUID projectId = UUID.randomUUID();
        var second = new RemodelingRequirement(UUID.randomUUID(), projectId, UUID.randomUUID(), 1);
        var first = new RemodelingRequirement(UUID.randomUUID(), projectId, UUID.randomUUID(), 0);

        var project = RemodelingProject.create(projectId, UUID.randomUUID(), UUID.randomUUID(),
                "Proyecto", null, List.of(second, first), Instant.now());

        assertThat(project.requirements()).containsExactly(first, second);
    }

    @Test
    void rejectsEmptyOrDuplicateRequirements() {
        UUID projectId = UUID.randomUUID();
        assertThatThrownBy(() -> RemodelingProject.create(projectId, UUID.randomUUID(),
                UUID.randomUUID(), "Proyecto", null, List.of(), Instant.now()))
                .isInstanceOf(RemodelingConflictException.class)
                .hasMessageContaining("at least one");

        UUID requestId = UUID.randomUUID();
        var first = new RemodelingRequirement(UUID.randomUUID(), projectId, requestId, 0);
        var duplicate = new RemodelingRequirement(UUID.randomUUID(), projectId, requestId, 1);
        assertThatThrownBy(() -> RemodelingProject.create(projectId, UUID.randomUUID(),
                UUID.randomUUID(), "Proyecto", null, List.of(first, duplicate), Instant.now()))
                .isInstanceOf(RemodelingConflictException.class)
                .hasMessageContaining("unique");
    }
}
