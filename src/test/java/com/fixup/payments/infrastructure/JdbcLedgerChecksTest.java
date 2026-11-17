package com.fixup.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixup.payments.domain.LedgerInvariant;
import org.junit.jupiter.api.Test;

class JdbcLedgerChecksTest {

    @Test
    void everyInvariantHasItsQuery() {
        assertThat(JdbcLedgerChecks.OFFENDERS.keySet()).containsExactlyInAnyOrder(LedgerInvariant.values());
    }
}
