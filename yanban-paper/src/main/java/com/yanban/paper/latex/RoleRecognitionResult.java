package com.yanban.paper.latex;

import java.util.List;

public record RoleRecognitionResult(
        List<RecognizedSectionRole> sectionRoles,
        List<StructureClarificationCandidate> clarifications,
        List<LatexSectionRole> missingRoles
) {
}
