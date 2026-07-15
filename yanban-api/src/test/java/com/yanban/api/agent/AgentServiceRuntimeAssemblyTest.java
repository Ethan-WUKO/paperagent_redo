package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.observability.TraceIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AgentServiceRuntimeAssemblyTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void websocketRuntimeAssemblyCreatesServerOwnedTraceWhenUpgradeMdcIsGone() {
        MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);

        String traceId = AgentService.resolvedRuntimeTraceId();

        assertThat(traceId).isNotBlank();
        assertThatCodeAsUuid(traceId);
    }

    @Test
    void httpRuntimeAssemblyPreservesFilterResolvedTrace() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "http-trace-17");

        assertThat(AgentService.resolvedRuntimeTraceId()).isEqualTo("http-trace-17");
    }

    private void assertThatCodeAsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
