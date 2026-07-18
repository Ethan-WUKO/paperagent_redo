package com.yanban.core.agent.worker;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** Fail closed: Worker authority and attestations cannot enter ordinary JSON. */
public final class WorkerServerOnlySerializer extends JsonSerializer<Object> {
    @Override
    public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        throw JsonMappingException.from(generator, "Worker server authority cannot be serialized");
    }
}
