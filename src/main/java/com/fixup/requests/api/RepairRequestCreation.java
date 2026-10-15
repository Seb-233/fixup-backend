package com.fixup.requests.api;

import com.fixup.identityaccess.api.CurrentActor;

/** Public command used by other modules to open a normal request in their transaction. */
public interface RepairRequestCreation {
    ProjectRepairRequestSnapshot open(CurrentActor actor, NewProjectRepairRequest request);
}
