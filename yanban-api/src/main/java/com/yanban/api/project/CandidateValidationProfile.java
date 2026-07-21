package com.yanban.api.project;

import java.util.List;

/** Server-owned compile/test profiles. The client never supplies argv or environment variables. */
public enum CandidateValidationProfile {
    MAVEN_TEST(List.of("mvn", "-o", "test")),
    MAVEN_VERIFY(List.of("mvn", "-o", "verify")),
    JAVA_SOURCE_RUN(List.of("java"));

    private final List<String> argv;

    CandidateValidationProfile(List<String> argv) {
        this.argv = List.copyOf(argv);
    }

    public List<String> argv(String selectedJavaSource) {
        if (this == JAVA_SOURCE_RUN) {
            if (selectedJavaSource == null || selectedJavaSource.isBlank()) {
                throw new IllegalArgumentException("JAVA_SOURCE_RUN requires one selected Java source");
            }
            return List.of("java", selectedJavaSource);
        }
        return argv;
    }
}
