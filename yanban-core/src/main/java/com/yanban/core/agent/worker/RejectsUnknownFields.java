package com.yanban.core.agent.worker;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Rejects schema drift even when a surrounding ObjectMapper ignores unknown properties. */
interface RejectsUnknownFields {
    @JsonAnySetter
    default void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("unknown worker contract field: " + name);
    }
}
