package com.fixup.remodelings;

import com.fixup.remodelings.api.RemodelingProgressStatus;
import com.fixup.remodelings.domain.RemodelingProgress;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemodelingProgressTest {
    @Test
    void calculatesEveryApprovedProgressState() {
        assertThat(RemodelingProgress.calculate(3, 0, 0).status())
                .isEqualTo(RemodelingProgressStatus.OPEN);
        assertThat(RemodelingProgress.calculate(3, 1, 0).status())
                .isEqualTo(RemodelingProgressStatus.PARTIALLY_ASSIGNED);
        assertThat(RemodelingProgress.calculate(3, 3, 0).status())
                .isEqualTo(RemodelingProgressStatus.FULLY_ASSIGNED);
        assertThat(RemodelingProgress.calculate(3, 3, 1).status())
                .isEqualTo(RemodelingProgressStatus.PARTIALLY_COMPLETED);
        assertThat(RemodelingProgress.calculate(3, 3, 3).status())
                .isEqualTo(RemodelingProgressStatus.COMPLETED);
    }

    @Test
    void rejectsImpossibleCounters() {
        assertThatThrownBy(() -> RemodelingProgress.calculate(2, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemodelingProgress.calculate(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
