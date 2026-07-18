package com.yanban.core.agent.worker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;

/** Fail closed: no JSON document can mint Worker server authority. */
public final class WorkerServerOnlyDeserializer extends JsonDeserializer<Object> {
    @Override
    public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        throw JsonMappingException.from(parser, "Worker server authority cannot be deserialized");
    }
}
