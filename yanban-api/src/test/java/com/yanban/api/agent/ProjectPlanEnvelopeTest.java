package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProjectPlanEnvelopeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesPlannerPayloadButRestoresOnlyTheServerOwnedEnvelope() {
        String raw = ProjectPlanEnvelope.wrap(json, "{\"projectId\":999}", new ProjectRuntimeContext(7L, 11L));
        assertThat(ProjectPlanEnvelope.restore(json, raw, 7L)).isEqualTo(new ProjectRuntimeContext(7L, 11L));
        assertThat(ProjectPlanEnvelope.restore(json, "{\"projectId\":999,\"capability\":\"PROJECT_READ\"}", 7L)).isNull();
    }

    @Test
    void normalPlanWrapperHasNoCapabilityAndForgedWrapperFailsClosed() {
        String ordinary = ProjectPlanEnvelope.wrap(json, "{\"summary\":\"ordinary\"}", null);
        assertThat(ProjectPlanEnvelope.restore(json, ordinary, 7L)).isNull();
        assertThatThrownBy(() -> ProjectPlanEnvelope.restore(json,
                "{\"schemaVersion\":\"project_plan_envelope_v1\",\"plannerRawJson\":\"{}\",\"serverAttestedProjectContext\":{\"projectId\":9,\"capability\":\"PROJECT_READ\"},\"extra\":true}", 7L))
                .isInstanceOf(IllegalStateException.class);
    }
}
