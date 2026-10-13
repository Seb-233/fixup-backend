package com.fixup.remodelings.application;

import com.fixup.identityaccess.api.CurrentActor;
import com.fixup.jobs.api.JobDirectory;
import com.fixup.jobs.api.JobSnapshot;
import com.fixup.jobs.api.JobStatus;
import com.fixup.remodelings.domain.RemodelingProgress;
import com.fixup.remodelings.domain.RemodelingProject;
import com.fixup.remodelings.domain.RemodelingRequirement;
import com.fixup.requests.api.ProjectRepairRequestSnapshot;
import com.fixup.requests.api.RepairRequestDirectory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class RemodelingViewAssembler {
    private final RepairRequestDirectory requests;
    private final JobDirectory jobs;

    RemodelingViewAssembler(RepairRequestDirectory requests, JobDirectory jobs) {
        this.requests = requests;
        this.jobs = jobs;
    }

    RemodelingProjectDetail detail(RemodelingProject project, CurrentActor actor) {
        var context = load(List.of(project), actor);
        return detail(project, context);
    }

    List<RemodelingProjectSummary> summaries(List<RemodelingProject> projects,
            CurrentActor actor) {
        if (projects.isEmpty()) {
            return List.of();
        }
        var context = load(projects, actor);
        return projects.stream().map(project -> {
            var progress = progress(project, context);
            return new RemodelingProjectSummary(project.id(), project.propertyId(), project.title(),
                    progress.status(), progress.requirementCount(), progress.assignedCount(),
                    progress.completedCount(), project.createdAt());
        }).toList();
    }

    private RemodelingProjectDetail detail(RemodelingProject project, Context context) {
        var requirementViews = project.requirements().stream()
                .map(requirement -> requirementView(requirement, context))
                .toList();
        int assigned = (int) requirementViews.stream()
                .filter(view -> view.assignedFixerUserId() != null || view.jobId() != null)
                .count();
        int completed = (int) requirementViews.stream()
                .filter(view -> view.jobStatus() == JobStatus.COMPLETED)
                .count();
        var progress = RemodelingProgress.calculate(requirementViews.size(), assigned, completed);
        return new RemodelingProjectDetail(project.id(), project.propertyId(), project.ownerUserId(),
                project.title(), project.description(), progress.status(), progress.requirementCount(),
                progress.assignedCount(), progress.completedCount(), requirementViews,
                project.createdAt(), project.updatedAt());
    }

    private RemodelingProgress progress(RemodelingProject project, Context context) {
        int assigned = 0;
        int completed = 0;
        for (var requirement : project.requirements()) {
            var request = requireRequest(context.requestsById(), requirement.requestId());
            var job = context.jobsByRequestId().get(requirement.requestId());
            if (request.assignedFixerUserId() != null || job != null) {
                assigned++;
            }
            if (job != null && job.status() == JobStatus.COMPLETED) {
                completed++;
            }
        }
        return RemodelingProgress.calculate(project.requirements().size(), assigned, completed);
    }

    private RemodelingRequirementSummary requirementView(RemodelingRequirement requirement,
            Context context) {
        var request = requireRequest(context.requestsById(), requirement.requestId());
        var job = context.jobsByRequestId().get(requirement.requestId());
        return new RemodelingRequirementSummary(requirement.id(), requirement.requestId(),
                requirement.position(), request.specialty(), request.status(),
                request.assignedFixerUserId(), job == null ? null : job.jobId(),
                job == null ? null : job.status());
    }

    private Context load(List<RemodelingProject> projects, CurrentActor actor) {
        var requestIds = projects.stream().flatMap(project -> project.requirements().stream())
                .map(RemodelingRequirement::requestId).toList();
        Map<UUID, ProjectRepairRequestSnapshot> requestsById = requests.findAll(requestIds).stream()
                .collect(Collectors.toMap(ProjectRepairRequestSnapshot::requestId,
                        Function.identity()));
        if (requestsById.size() != requestIds.size()) {
            throw new IllegalStateException("A remodeling requirement references a missing request");
        }
        Map<UUID, JobSnapshot> jobsByRequest = jobs.findVisibleByRequestIds(actor, requestIds).stream()
                .collect(Collectors.toMap(JobSnapshot::requestId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        return new Context(requestsById, jobsByRequest);
    }

    private ProjectRepairRequestSnapshot requireRequest(
            Map<UUID, ProjectRepairRequestSnapshot> requestsById, UUID requestId) {
        var request = requestsById.get(requestId);
        if (request == null) {
            throw new IllegalStateException("A remodeling requirement references a missing request");
        }
        return request;
    }

    private record Context(Map<UUID, ProjectRepairRequestSnapshot> requestsById,
            Map<UUID, JobSnapshot> jobsByRequestId) {
    }
}
