package com.yanban.core.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResearchToolContractsTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void firstBatchIsReadOnlyContractOnlyWithDistinctTypedOutputSchemas() {
        List<ResearchToolContract> contracts = ResearchToolContracts.all();

        assertThat(contracts).extracting(contract -> contract.definition().name()).containsExactly(
                "project_latex_outline", "project_bibtex_audit", "project_code_symbols",
                "project_experiment_summary", "project_cross_material_search");
        assertThat(contracts).extracting(ResearchToolContract::itemType).containsExactly(
                ResearchToolItemType.LATEX_OUTLINE, ResearchToolItemType.BIBTEX_AUDIT,
                ResearchToolItemType.CODE_SYMBOL, ResearchToolItemType.EXPERIMENT_SUMMARY,
                ResearchToolItemType.CROSS_MATERIAL_LINK);
        for (ResearchToolContract contract : contracts) {
            assertThat(contract.contractStatus()).isEqualTo("CONTRACT_ONLY");
            assertThat(contract.descriptor().sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.READ_ONLY);
            assertThat(contract.descriptor().confirmationPolicy()).isEqualTo(ToolDescriptor.ConfirmationPolicy.NEVER);
            assertThat(contract.descriptor().modelVisible()).isFalse();
            assertThat(contract.definition().parameters().path("additionalProperties").asBoolean()).isFalse();
            assertThat(contract.inputPolicy().allowedFields()).doesNotContain("projectId", "userId", "capability");
            assertThat(contract.outputSchema().toString()).doesNotContain("projectId", "userId", "capability", "absolutePath");
            JsonNode itemSchema = contract.outputSchema().path("properties").path("items").path("items");
            if (itemSchema.has("oneOf")) {
                itemSchema.path("oneOf").forEach(schema -> assertThat(schema.path("additionalProperties").asBoolean()).isFalse());
            } else assertThat(itemSchema.path("additionalProperties").asBoolean()).isFalse();
        }
        assertItemProperties(ResearchToolContracts.PROJECT_LATEX_OUTLINE, "kind", "identifier", "detail", "content");
        assertItemProperties(ResearchToolContracts.PROJECT_BIBTEX_AUDIT, "issue", "citationKey", "detail", "content");
        assertItemProperties(ResearchToolContracts.PROJECT_CODE_SYMBOLS, "kind", "qualifiedName", "dependencyReference", "content");
        assertItemProperties(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY, "assetType", "metricName", "value", "content");
        assertItemProperties(ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH, "concept", "relation", "linkedEvidence", "content");
    }

    @Test
    void everyOutcomeStatusSerializesToEveryToolOutputSchemaWithoutServerOnlyFields() throws Exception {
        for (ResearchToolContract contract : ResearchToolContracts.all()) {
            for (ResearchToolResultState state : ResearchToolResultState.values()) {
                ResearchToolOutcome outcome = outcome(contract, state);
                contract.validateOutcome(outcome);
                JsonNode serialized = json.readTree(json.writeValueAsString(outcome));

                assertSchema(contract.outputSchema(), serialized, "$" + contract.definition().name() + "/" + state);
                assertThat(serialized.toString()).doesNotContain("budgetUsage", "retryable", "trustedProjectId", "trustedUserId");
                assertThat(serialized.path("status").asText()).isEqualTo(state.name());
                assertThat(serialized.path("partial").asBoolean()).isEqualTo(state != ResearchToolResultState.COMPLETE
                        && state != ResearchToolResultState.EMPTY);
                assertThat(serialized.path("truncated").asBoolean()).isEqualTo(state == ResearchToolResultState.TRUNCATED);
                assertThat(serialized.path("parseFailed").asBoolean()).isEqualTo(state == ResearchToolResultState.PARSE_FAILED);
            }
        }
    }

    @Test
    void inputValidationFailsClosedForEmptyRequiredArraysBadArrayItemsDecimalsAndNonCanonicalPaths() {
        ResearchToolContract latex = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        assertCode(ResearchToolErrorCode.INVALID_ARGUMENT, () -> latex.inputPolicy().validate(json.createObjectNode().putArray("relativePaths")));
        ObjectNode spaced = json.createObjectNode(); spaced.putArray("relativePaths").add(" paper/main.tex ");
        assertCode(ResearchToolErrorCode.PATH_OUTSIDE_PROJECT, () -> latex.callKey(version(), spaced));

        ResearchToolContract experiments = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY);
        ObjectNode nonTextMetric = json.createObjectNode(); nonTextMetric.putArray("relativePaths").add("runs/metrics.csv"); nonTextMetric.putArray("metricNames").add(3);
        assertCode(ResearchToolErrorCode.INVALID_ARGUMENT, () -> experiments.inputPolicy().validate(nonTextMetric));
        ObjectNode blankMetric = json.createObjectNode(); blankMetric.putArray("relativePaths").add("runs/metrics.csv"); blankMetric.putArray("metricNames").add(" ");
        assertCode(ResearchToolErrorCode.INVALID_ARGUMENT, () -> experiments.inputPolicy().validate(blankMetric));
        ObjectNode decimal = json.createObjectNode(); decimal.putArray("relativePaths").add("runs/metrics.csv"); decimal.put("maxRowsPerFile", 3.5);
        assertCode(ResearchToolErrorCode.INVALID_ARGUMENT, () -> experiments.inputPolicy().validate(decimal));
    }

    @Test
    void runtimeAuthorityCannotBeSerializedAndProjectVersionRejectsHostPaths() {
        ResearchRuntimeScope scope = new ResearchRuntimeScope(42L, 7L, Set.of("research:project-read"), version());
        assertThatThrownBy(() -> json.writeValueAsString(scope)).isInstanceOf(JsonProcessingException.class);
        for (String unsafe : List.of("C:\\secret", "\\\\server\\share", "file:///secret", "https://host/version", "C:secret")) {
            assertThatThrownBy(() -> new ProjectVersionRef(unsafe)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void productionManifestSha256VersionIsNormalizedSerializedAndUsableInCallKey() throws Exception {
        String upper = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";
        ProjectVersionRef version = new ProjectVersionRef(upper);
        ObjectNode arguments = json.createObjectNode().put("query", "loss");
        ResearchCallKey key = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH)
                .callKey(version, arguments);

        assertThat(version.value()).isEqualTo(upper.toLowerCase(java.util.Locale.ROOT));
        assertThat(json.writeValueAsString(version)).isEqualTo('"' + upper.toLowerCase(java.util.Locale.ROOT) + '"');
        assertThat(key.value()).contains(version.value());
    }

    @Test
    void typedItemsPermitNaturalMissingOptionalFieldsAndStillMatchTheirSchemas() throws Exception {
        List<ResearchToolItem> optionalItems = List.of(
                new LatexOutlineItem("SECTION", null, "Introduction", content()),
                new BibtexAuditItem("MALFORMED_ENTRY", null, "missing opening brace", content()),
                new CodeSymbolItem("METHOD", "pkg.Main.run", null, content()),
                new ExperimentSummaryItem("CONFIG", null, "batch_size=32", content()));
        List<ResearchToolContract> contracts = List.of(
                ResearchToolContracts.byName(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                ResearchToolContracts.byName(ResearchToolContracts.PROJECT_BIBTEX_AUDIT),
                ResearchToolContracts.byName(ResearchToolContracts.PROJECT_CODE_SYMBOLS),
                ResearchToolContracts.byName(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY));
        for (int index = 0; index < contracts.size(); index++) {
            ResearchToolOutcome outcome = new ResearchToolOutcome(ResearchToolResultState.COMPLETE,
                    List.of(optionalItems.get(index)), List.of(evidence()), null, new ResearchBudgetUsage(1, 1, 1, 1));
            JsonNode serialized = json.readTree(json.writeValueAsString(outcome));
            assertSchema(contracts.get(index).outputSchema(), serialized, "$optional" + index);
            assertThat(serialized.path("items").get(0).size()).isEqualTo(3);
        }
    }

    @Test
    void outcomeStateAndEvidenceCoverageAreFailClosed() {
        ResearchToolItem latex = item(ResearchToolItemType.LATEX_OUTLINE);
        assertThatThrownBy(() -> new ResearchToolOutcome(ResearchToolResultState.COMPLETE, List.of(), List.of(), null,
                new ResearchBudgetUsage(0, 0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchToolOutcome(ResearchToolResultState.EMPTY, List.of(latex), List.of(), null,
                new ResearchBudgetUsage(1, 1, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchToolOutcome(ResearchToolResultState.PARTIAL, List.of(latex), List.of(evidence()), null,
                new ResearchBudgetUsage(1, 1, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchToolOutcome(ResearchToolResultState.TRUNCATED, List.of(latex), List.of(evidence()),
                ResearchToolErrorCode.PARTIAL_RESULT, new ResearchBudgetUsage(1, 1, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchToolOutcome(ResearchToolResultState.PARSE_FAILED, List.of(latex), List.of(),
                ResearchToolErrorCode.PARSER_FAILURE, new ResearchBudgetUsage(1, 1, 0, 0))).isInstanceOf(IllegalArgumentException.class);

        ResearchToolContract latexContract = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        ResearchToolOutcome missingEnvelopeEvidence = new ResearchToolOutcome(ResearchToolResultState.COMPLETE, List.of(latex), List.of(),
                null, new ResearchBudgetUsage(1, 1, 0, 1));
        assertCode(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE, () -> latexContract.validateOutcome(missingEnvelopeEvidence));
        ResearchToolContract crossContract = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH);
        ResearchToolItem cross = new CrossMaterialLinkItem("rate", "configured-by", List.of(evidence(), secondEvidence()), content());
        ResearchToolOutcome missingLinkedEvidence = new ResearchToolOutcome(ResearchToolResultState.COMPLETE, List.of(cross), List.of(evidence()),
                null, new ResearchBudgetUsage(1, 1, 1, 1));
        assertCode(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE, () -> crossContract.validateOutcome(missingLinkedEvidence));
    }

    @Test
    void pathRejectsWindowsAdsAndControlAliasesAndSchemaFreezesEvidenceConstraints() {
        for (String unsafe : List.of("paper/main.tex:secret", "paper/\u0000main.tex", "paper/\u001fmain.tex")) {
            assertThatThrownBy(() -> ProjectRelativePath.of(unsafe)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ProjectRelativePath.of("论文/实验 报告.tex").value()).isEqualTo("论文/实验 报告.tex");
        JsonNode evidence = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_CODE_SYMBOLS).outputSchema()
                .path("properties").path("evidenceRefs").path("items");
        assertThat(evidence.path("properties").path("trustLabel").path("enum"))
                .extracting(JsonNode::asText).containsExactly("SERVER_ATTESTED_METADATA", "UNTRUSTED_PROJECT_CONTENT");
        assertThat(evidence.path("properties").path("fileHash").path("pattern").asText()).isEqualTo("^[a-f0-9]{64}$");
        assertThat(evidence.path("properties").path("parserVersion").path("pattern").asText()).isEqualTo(ParserVersionRef.PATTERN);
        assertThat(evidence.path("properties").path("range").path("properties").path("startLine").path("minimum").asInt()).isEqualTo(1);
        assertThat(evidence.path("properties").path("projectVersion").path("oneOf")).hasSize(2);
    }

    @Test
    void requiredSchemaArrayIsBuiltOnceAndLegacyDescriptorStillDefaultsToUnknown() {
        ResearchToolInputPolicy policy = new ResearchToolInputPolicy(Set.of("alpha", "beta"), Set.of("alpha", "beta"),
                Map.of(), Map.of(), Map.of("alpha", 10, "beta", 10), Set.of(), Set.of(), Map.of());
        assertThat(ResearchToolContracts.inputSchema(policy).path("required"))
                .extracting(JsonNode::asText).containsExactly("alpha", "beta");
        ToolDescriptor legacy = new ToolDescriptor("legacy", null, null, null, null, null, null, null);
        assertThat(legacy.sideEffectType()).isEqualTo(ToolDescriptor.SideEffectType.UNKNOWN);
    }

    @Test
    void outcomeRejectsMixedToolItemTypesAndDoesNotAcceptCallerControlledRetryability() {
        ResearchToolContract latex = ResearchToolContracts.byName(ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        ResearchToolOutcome wrong = new ResearchToolOutcome(ResearchToolResultState.COMPLETE,
                List.of(item(ResearchToolItemType.BIBTEX_AUDIT)), List.of(evidence()), null, new ResearchBudgetUsage(1, 1, 1, 1));
        assertCode(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE, () -> latex.validateOutcome(wrong));
        assertThat(new ResearchToolOutcome(ResearchToolResultState.PARSE_FAILED, List.of(), List.of(),
                ResearchToolErrorCode.PARSER_FAILURE, new ResearchBudgetUsage(0, 0, 0, 0)).retryable()).isFalse();
    }

    private void assertItemProperties(String name, String... fields) {
        JsonNode itemSchema = ResearchToolContracts.byName(name).outputSchema().path("properties").path("items").path("items");
        if (itemSchema.has("oneOf")) itemSchema = itemSchema.path("oneOf").get(0);
        JsonNode properties = itemSchema.path("properties");
        for (String field : fields) assertThat(properties.has(field)).isTrue();
    }

    private ResearchToolOutcome outcome(ResearchToolContract contract, ResearchToolResultState state) {
        List<ResearchToolItem> items = state == ResearchToolResultState.EMPTY || state == ResearchToolResultState.PARSE_FAILED ? List.of() : List.of(item(contract.itemType()));
        List<ResearchEvidenceRef> evidence = state == ResearchToolResultState.EMPTY || state == ResearchToolResultState.PARSE_FAILED ? List.of() : evidenceFor(contract.itemType());
        ResearchToolErrorCode error = state == ResearchToolResultState.PARSE_FAILED ? ResearchToolErrorCode.PARSER_FAILURE
                : state == ResearchToolResultState.TRUNCATED ? ResearchToolErrorCode.RESULT_TRUNCATED
                : state == ResearchToolResultState.PARTIAL ? ResearchToolErrorCode.PARTIAL_RESULT : null;
        return new ResearchToolOutcome(state, items, evidence, error,
                new ResearchBudgetUsage(1, items.size(), evidence.size(), 10));
    }

    private ResearchToolItem item(ResearchToolItemType type) {
        UntrustedResearchContent content = new UntrustedResearchContent("source data", evidence());
        return switch (type) {
            case LATEX_OUTLINE -> new LatexOutlineItem("SECTION", "sec:intro", "Introduction", content);
            case BIBTEX_AUDIT -> new BibtexAuditItem("MISSING_FIELD", "doe2024", "year", content);
            case CODE_SYMBOL -> new CodeSymbolItem("METHOD", "pkg.Main.run", "pkg.Helper", content);
            case EXPERIMENT_SUMMARY -> new ExperimentSummaryItem("CSV_METRIC", "accuracy", "0.95", content);
            case CROSS_MATERIAL_LINK -> new CrossMaterialLinkItem("learning rate", "configured-by", List.of(evidence(), evidence()), content);
            case LITERAL_MATCH -> new LiteralMatchItem("learning", ProjectRelativePath.of("paper/main.tex"), 1, content);
        };
    }

    private ResearchEvidenceRef evidence() {
        return new ResearchEvidenceRef(version(), ProjectRelativePath.of("paper/main.tex"), new FileHash(HASH),
                new SourceRange(1, 2), new ParserVersionRef("parser@1"), TrustLabel.SERVER_ATTESTED_METADATA);
    }

    private ResearchEvidenceRef secondEvidence() {
        return new ResearchEvidenceRef(version(), ProjectRelativePath.of("configs/train.yaml"), new FileHash(HASH),
                new SourceRange(3, 3), new ParserVersionRef("parser@1"), TrustLabel.SERVER_ATTESTED_METADATA);
    }

    private UntrustedResearchContent content() { return new UntrustedResearchContent("source data", evidence()); }

    private List<ResearchEvidenceRef> evidenceFor(ResearchToolItemType type) {
        return type == ResearchToolItemType.CROSS_MATERIAL_LINK ? List.of(evidence(), secondEvidence()) : List.of(evidence());
    }

    private ProjectVersionRef version() { return new ProjectVersionRef("manifest-sha256:abc"); }

    private static void assertCode(ResearchToolErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ResearchContractException.class)
                .extracting(error -> ((ResearchContractException) error).errorCode()).isEqualTo(code);
    }

    private static void assertSchema(JsonNode schema, JsonNode value, String at) {
        assertThat(value.isObject() || value.isArray() || value.isTextual() || value.isIntegralNumber() || value.isBoolean())
                .as(at + " has a supported JSON value").isTrue();
        if (schema.has("enum")) assertThat(schema.path("enum")).anySatisfy(allowed -> assertThat(allowed).isEqualTo(value));
        if (schema.has("oneOf")) assertThat(schema.path("oneOf")).anySatisfy(option -> assertSchema(option, value, at + ".oneOf"));
        if (schema.has("pattern")) assertThat(value.isTextual()).as(at + " pattern string").isTrue();
        if (schema.has("pattern")) assertThat(value.textValue()).matches(schema.path("pattern").asText());
        String type = schema.path("type").asText();
        if (type.isBlank()) return;
        switch (type) {
            case "object" -> {
                assertThat(value.isObject()).as(at + " object").isTrue();
                for (JsonNode required : schema.path("required")) assertThat(value.has(required.asText())).as(at + " required " + required.asText()).isTrue();
                if (!schema.path("additionalProperties").asBoolean(true)) {
                    value.fieldNames().forEachRemaining(field -> assertThat(schema.path("properties").has(field)).as(at + " known " + field).isTrue());
                }
                value.fields().forEachRemaining(entry -> assertSchema(schema.path("properties").path(entry.getKey()), entry.getValue(), at + "." + entry.getKey()));
            }
            case "array" -> {
                assertThat(value.isArray()).as(at + " array").isTrue();
                if (schema.has("minItems")) assertThat(value.size()).isGreaterThanOrEqualTo(schema.path("minItems").asInt());
                if (schema.has("maxItems")) assertThat(value.size()).isLessThanOrEqualTo(schema.path("maxItems").asInt());
                for (JsonNode item : value) assertSchema(schema.path("items"), item, at + "[]");
            }
            case "string" -> {
                assertThat(value.isTextual()).as(at + " string").isTrue();
                if (schema.has("minLength")) assertThat(value.textValue().length()).isGreaterThanOrEqualTo(schema.path("minLength").asInt());
                if (schema.has("maxLength")) assertThat(value.textValue().length()).isLessThanOrEqualTo(schema.path("maxLength").asInt());
            }
            case "integer" -> {
                assertThat(value.isIntegralNumber()).as(at + " integer").isTrue();
                if (schema.has("minimum")) assertThat(value.longValue()).isGreaterThanOrEqualTo(schema.path("minimum").asLong());
            }
            case "boolean" -> assertThat(value.isBoolean()).as(at + " boolean").isTrue();
            default -> throw new AssertionError("unsupported schema type at " + at + ": " + type);
        }
    }
}
