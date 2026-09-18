package com.fixup.fixers.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FixerProfileJpaRepository extends JpaRepository<FixerProfileEntity, UUID> {
}
