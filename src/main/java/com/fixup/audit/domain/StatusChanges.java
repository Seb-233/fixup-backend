package com.fixup.audit.domain;

import com.fixup.audit.api.AuditedResourceType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatusChanges {
    void save(StatusChangeEntry entry);

    List<StatusChangeEntry> findRecent(Optional<AuditedResourceType> resourceType, Optional<UUID> resourceId, int limit);
}
