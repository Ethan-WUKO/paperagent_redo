package com.yanban.knowledge.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RagSpikeFixtureLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path fixtureRoot;

    public RagSpikeFixtureLoader(Path fixtureRoot) {
        this.fixtureRoot = fixtureRoot;
    }

    public List<RagSpikeDocumentFixture> loadDocuments() throws IOException {
        return OBJECT_MAPPER.readValue(
                Files.readString(fixtureRoot.resolve("documents.json")),
                new TypeReference<>() {
                }
        );
    }

    public List<RagSpikeEvalCase> loadCases() throws IOException {
        return OBJECT_MAPPER.readValue(
                Files.readString(fixtureRoot.resolve("cases.json")),
                new TypeReference<>() {
                }
        );
    }

    public String readDocumentText(RagSpikeDocumentFixture document) throws IOException {
        return Files.readString(fixtureRoot.resolve(document.path()));
    }
}
