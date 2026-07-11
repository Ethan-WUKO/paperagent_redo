package com.yanban.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void failureCarriesTypedErrorAndItsRetryClassification() {
        ToolResult result = ToolResult.failure("call-1", "search_web", ToolErrorCode.TIMEOUT, "upstream timed out");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TIMEOUT);
        assertThat(result.retryable()).isTrue();
        assertThat(result.evidenceRefs()).isEmpty();
        assertThat(result.version()).isEqualTo("v1");
    }

    @Test
    void descriptorDefaultsToUnknownSideEffectsAndSynchronousExecution() {
        ToolDescriptor descriptor = new ToolDescriptor("unregistered_tool", null, null, null, null, null, null, null);

        assertThat(descriptor.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.UNKNOWN);
        assertThat(descriptor.asyncMode()).isEqualTo(ToolDescriptor.AsyncMode.SYNC);
        assertThat(descriptor.supportedProfiles()).isEqualTo(List.of());
    }

    @Test
    void sideEffectingDescriptorCannotDefaultToNoConfirmation() {
        ToolDescriptor descriptor = new ToolDescriptor("modify", "v1", "test", List.of(), List.of(), List.of(),
                ToolDescriptor.SideEffectType.MODIFY, null, null, null, null, true);

        assertThat(descriptor.confirmationPolicy()).isEqualTo(ToolDescriptor.ConfirmationPolicy.ON_SIDE_EFFECT);
    }
}
