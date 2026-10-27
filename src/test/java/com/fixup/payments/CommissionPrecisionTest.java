package com.fixup.payments;

import com.fixup.payments.domain.CommissionPolicy;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommissionPrecisionTest {
    @Test void tenPercentNeverOverflowsAndKeepsWholePesoRounding() {
        for(long gross:new long[]{1,9,10,11,99999,9007199254740991L,Long.MAX_VALUE}) {
            assertThat(CommissionPolicy.commissionFor(gross)).isEqualTo(BigInteger.valueOf(gross).divide(BigInteger.TEN).longValueExact());
        }
    }
}
