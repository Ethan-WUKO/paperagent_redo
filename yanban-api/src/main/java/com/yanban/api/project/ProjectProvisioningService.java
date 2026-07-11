package com.yanban.api.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal-only trusted provisioning boundary. It is intentionally not exposed by a controller.
 * The caller is trusted to supply the owner userId; a future user-facing endpoint must derive it
 * from the authenticated principal and must never deserialize caller-selected ownership.
 */
@Service
public class ProjectProvisioningService {

    private final ProjectRepository projects;
    private final LocalServerProjectRootProvider localRootProvider;
    private final ObjectMapper objectMapper;

    public ProjectProvisioningService(ProjectRepository projects,
                                      LocalServerProjectRootProvider localRootProvider,
                                      ObjectMapper objectMapper) {
        this.projects = projects;
        this.localRootProvider = localRootProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Project provision(ProjectProvisioningRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.name() == null || request.name().isBlank() || request.name().length() > 255) {
            throw new InvalidProjectPathException("Project provisioning request is invalid");
        }
        if (request.includeRules() == null || request.includeRules().isEmpty()
                || request.includeRules().stream().anyMatch(rule -> rule == null || rule.isBlank())) {
            throw new InvalidProjectPathException("Project include rules must be explicit and non-empty");
        }
        ProjectPathPolicy.validateRules(request.includeRules(), "includeRules", false);
        ProjectPathPolicy.validateRules(request.ignoreRules() == null ? List.of() : request.ignoreRules(), "ignoreRules", true);
        Path canonicalRoot = localRootProvider.authorizeAbsoluteProjectFolder(request.projectFolder());
        // The caller-supplied absolute path is only a binding input. Do not retain it as the
        // user-facing root-path value; canonicalRootPath stays internal to the root provider.
        Project project = new Project(request.userId(), request.name().trim(), ".",
                canonicalRoot.toString(), serialize(request.includeRules()), serialize(request.ignoreRules()));
        return projects.save(project);
    }

    private String serialize(List<String> rules) {
        try {
            return objectMapper.writeValueAsString(rules == null ? List.of() : rules);
        } catch (JsonProcessingException ex) {
            throw new InvalidProjectPathException("Project rules are invalid", ex);
        }
    }
}
