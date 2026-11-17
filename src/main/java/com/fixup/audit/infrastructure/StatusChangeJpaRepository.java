package com.fixup.audit.infrastructure;

import com.fixup.audit.api.AuditedResourceType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface StatusChangeJpaRepository extends JpaRepository<StatusChangeEntity, UUID> {
    List<StatusChangeEntity> findByResourceTypeAndResourceIdOrderByChangedAtDescIdDesc(
            AuditedResourceType resourceType, UUID resourceId, Pageable pageable);

    List<StatusChangeEntity> findByResourceTypeOrderByChangedAtDescIdDesc(
            AuditedResourceType resourceType, Pageable pageable);

    List<StatusChangeEntity> findAllByOrderByChangedAtDescIdDesc(Pageable pageable);
}
