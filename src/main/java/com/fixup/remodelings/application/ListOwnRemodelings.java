package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.remodelings.domain.RemodelingProjects;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListOwnRemodelings {
    private final RemodelingProjects projects;
    private final RemodelingViewAssembler views;

    ListOwnRemodelings(RemodelingProjects projects, RemodelingViewAssembler views) {
        this.projects = projects;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public List<RemodelingProjectSummary> execute(CurrentActor actor) {
        RemodelingAccess.requireActiveOwner(actor);
        return views.summaries(projects.findByOwner(actor.internalUserId()), actor);
    }
}
