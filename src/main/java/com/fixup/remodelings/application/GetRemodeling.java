package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.remodelings.api.RemodelingNotFoundException;
import com.fixup.remodelings.domain.RemodelingProjects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetRemodeling {
    private final RemodelingProjects projects;
    private final RemodelingViewAssembler views;

    GetRemodeling(RemodelingProjects projects, RemodelingViewAssembler views) {
        this.projects = projects;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public RemodelingProjectDetail execute(CurrentActor actor, UUID projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RemodelingNotFoundException(projectId));
        RemodelingAccess.requireCanRead(actor, project);
        return views.detail(project, actor);
    }
}
