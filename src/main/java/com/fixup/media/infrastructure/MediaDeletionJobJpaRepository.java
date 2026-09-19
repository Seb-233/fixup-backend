package com.fixup.media.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaDeletionJobJpaRepository extends JpaRepository<MediaDeletionJobEntity, UUID> {
    List<MediaDeletionJobEntity> findByStatus(String status);
}
