package com.fixup.remodelings.domain;

import com.fixup.remodelings.api.RemodelingProgressStatus;

public record RemodelingProgress(RemodelingProgressStatus status, int requirementCount,
        int assignedCount, int completedCount) {

    public static RemodelingProgress calculate(int requirementCount, int assignedCount,
            int completedCount) {
        if (requirementCount < 1 || assignedCount < 0 || completedCount < 0
                || completedCount > assignedCount || assignedCount > requirementCount) {
            throw new IllegalArgumentException("Inconsistent remodeling progress counters");
        }
        RemodelingProgressStatus status;
        if (completedCount == requirementCount) {
            status = RemodelingProgressStatus.COMPLETED;
        } else if (completedCount > 0) {
            status = RemodelingProgressStatus.PARTIALLY_COMPLETED;
        } else if (assignedCount == 0) {
            status = RemodelingProgressStatus.OPEN;
        } else if (assignedCount == requirementCount) {
            status = RemodelingProgressStatus.FULLY_ASSIGNED;
        } else {
            status = RemodelingProgressStatus.PARTIALLY_ASSIGNED;
        }
        return new RemodelingProgress(status, requirementCount, assignedCount, completedCount);
    }
}
