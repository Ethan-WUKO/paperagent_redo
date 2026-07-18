package com.yanban.core.agent.worker;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ParserVersionRef;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.SourceRange;
import com.yanban.core.research.TrustLabel;
import java.io.IOException;
import java.util.Set;

/** Strict Worker-boundary reader for the shared ResearchEvidenceRef projection. */
public final class StrictResearchEvidenceRefDeserializer extends StdDeserializer<ResearchEvidenceRef> {
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
            "projectVersion", "relativePath", "fileHash", "range", "parserVersion", "trustLabel");
    private static final Set<String> RANGE_FIELDS = Set.of("startLine", "endLine");

    public StrictResearchEvidenceRefDeserializer() {
        super(ResearchEvidenceRef.class);
    }

    @Override
    public ResearchEvidenceRef deserialize(JsonParser parser,
                                           com.fasterxml.jackson.databind.DeserializationContext context)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        requireExactObject(parser, node, EVIDENCE_FIELDS, "ResearchEvidenceRef");
        JsonNode range = node.path("range");
        requireExactObject(parser, range, RANGE_FIELDS, "SourceRange");
        if (!node.path("projectVersion").isTextual() || !node.path("relativePath").isTextual()
                || !node.path("fileHash").isTextual() || !node.path("parserVersion").isTextual()
                || !node.path("trustLabel").isTextual() || !range.path("startLine").canConvertToInt()
                || !range.path("endLine").canConvertToInt()) {
            throw JsonMappingException.from(parser, "ResearchEvidenceRef has invalid field types");
        }
        try {
            return new ResearchEvidenceRef(
                    new ProjectVersionRef(node.path("projectVersion").textValue()),
                    ProjectRelativePath.of(node.path("relativePath").textValue()),
                    new FileHash(node.path("fileHash").textValue()),
                    new SourceRange(range.path("startLine").intValue(), range.path("endLine").intValue()),
                    new ParserVersionRef(node.path("parserVersion").textValue()),
                    TrustLabel.valueOf(node.path("trustLabel").textValue()));
        } catch (IllegalArgumentException ex) {
            throw JsonMappingException.from(parser, "ResearchEvidenceRef is invalid", ex);
        }
    }

    private static void requireExactObject(JsonParser parser, JsonNode node, Set<String> fields,
                                           String label) throws JsonMappingException {
        if (!node.isObject() || node.size() != fields.size()
                || fields.stream().anyMatch(field -> !node.has(field))) {
            throw JsonMappingException.from(parser, label + " must contain exactly its declared fields");
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!fields.contains(names.next())) {
                throw JsonMappingException.from(parser, label + " contains an unknown field");
            }
        }
    }
}
