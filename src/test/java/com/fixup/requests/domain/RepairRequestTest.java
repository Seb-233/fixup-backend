package com.fixup.requests.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixup.fixers.api.Specialty;
import com.fixup.requests.api.RepairRequestConflictException;
import com.fixup.requests.api.RepairRequestStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepairRequestTest {

    @Test
    void allows_null_propertyId_in_constructor_for_legacy_rows() {
        var request = new RepairRequest(UUID.randomUUID(), null, UUID.randomUUID(), Specialty.PLUMBING, "Title", "Desc", List.of(), RepairRequestStatus.OPEN, null, Instant.now(), Instant.now());
        assertThat(request.propertyId()).isNull();
    }

    @Test
    void rejects_null_propertyId_in_open() {
        assertThatThrownBy(() -> RepairRequest.open(UUID.randomUUID(), null, UUID.randomUUID(), Specialty.PLUMBING, "Title", "Desc", List.of(), Instant.now()))
                .isInstanceOf(RepairRequestConflictException.class)
                .hasMessageContaining("A propertyId is required");
    }

    @Test
    void assign_preserves_propertyId() {
        var propertyId = UUID.randomUUID();
        var request = RepairRequest.open(UUID.randomUUID(), propertyId, UUID.randomUUID(), Specialty.PLUMBING, "Title", "Desc", List.of(), Instant.now());
        var assigned = request.assign(UUID.randomUUID(), Instant.now());
        assertThat(assigned.propertyId()).isEqualTo(propertyId);
    }
}