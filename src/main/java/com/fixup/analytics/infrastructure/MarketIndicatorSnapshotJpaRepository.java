package com.fixup.analytics.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

interface MarketIndicatorSnapshotJpaRepository extends JpaRepository<MarketIndicatorSnapshotEntity, String> {
}
