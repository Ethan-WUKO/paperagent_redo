package com.yanban.api.agent;

import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import java.util.List;
import org.springframework.stereotype.Component;

/** Revalidates trusted observations against the currently authorized Project manifest. */
@Component
public class ProjectEvidenceValidator {
    private final ProjectService projects;

    public ProjectEvidenceValidator(ProjectService projects) {
        this.projects = projects;
    }

    public EvidenceLedger current(Long userId, ProjectRuntimeContext context, EvidenceLedger evidence) {
        if (context == null || evidence == null || !context.userId().equals(userId)) return EvidenceLedger.empty();
        ProjectManifestResponse manifest = projects.manifest(userId, context.projectId());
        List<EvidenceRef> current = evidence.evidence().stream().filter(ref -> isTrusted(ref)
                && belongsToProject(ref, context.projectId())
                && manifest.files().stream().anyMatch(file -> file.path().equals(ref.file())
                && file.sha256().equals(ref.version()))).toList();
        return new EvidenceLedger(current);
    }

    static boolean isTrusted(EvidenceRef ref) {
        return ref != null && ref.id() != null && (ref.id().startsWith("trusted-tool:")
                || ref.id().startsWith("trusted-plan:"));
    }

    private static boolean belongsToProject(EvidenceRef ref, Long projectId) {
        if (ref == null || ref.id() == null || projectId == null) return false;
        return ref.id().startsWith("trusted-tool:" + projectId + ":")
                || ref.id().startsWith("trusted-plan:" + projectId + ":");
    }
}
