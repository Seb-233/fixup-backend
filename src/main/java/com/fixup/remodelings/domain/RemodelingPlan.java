package com.fixup.remodelings.domain;

import com.fixup.remodelings.api.RemodelingConflictException;
import com.fixup.remodelings.api.RemodelingGoal;
import java.time.Instant;
import java.time.LocalDate;

/** FR-UC-26: el objetivo de la remodelación y, si es pre-arriendo, el canon y la fecha meta. */
public record RemodelingPlan(RemodelingGoal goal, Long targetRent, LocalDate targetReadyDate,
        Instant listingPublishedAt) {

    public static final RemodelingPlan IMPROVEMENT = new RemodelingPlan(RemodelingGoal.IMPROVEMENT, null, null, null);

    public RemodelingPlan {
        goal = goal == null ? RemodelingGoal.IMPROVEMENT : goal;
        if (goal == RemodelingGoal.PRE_RENTAL && (targetRent == null || targetRent <= 0)) {
            throw new RemodelingConflictException("TARGET_RENT_REQUIRED",
                    "A pre-rental remodeling needs the monthly rent it aims for");
        }
        if (goal == RemodelingGoal.IMPROVEMENT) {
            targetRent = null;
        }
    }

    public RemodelingPlan published(Instant at) {
        return new RemodelingPlan(goal, targetRent, targetReadyDate, at);
    }
}
