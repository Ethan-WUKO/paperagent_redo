package com.yanban.core.research;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** First-batch schemas only; no executor or ToolRegistry registration is present here. */
public final class ResearchToolContracts {

    public static final String PROJECT_LATEX_OUTLINE = "project_latex_outline";
    public static final String PROJECT_BIBTEX_AUDIT = "project_bibtex_audit";
    public static final String PROJECT_CODE_SYMBOLS = "project_code_symbols";
    public static final String PROJECT_EXPERIMENT_SUMMARY = "project_experiment_summary";
    public static final String PROJECT_CROSS_MATERIAL_SEARCH = "project_cross_material_search";

    private static final List<ResearchToolContract> CONTRACTS = List.of(
            contract(PROJECT_LATEX_OUTLINE, "List LaTeX sections, formula references, citations, and floats.",
                    policy(Set.of("relativePaths"), Set.of("relativePaths", "includeFormulaReferences"),
                            Map.of("relativePaths", 20), Map.of("relativePaths", 256), Map.of(), Set.of("relativePaths"),
                            Set.of("includeFormulaReferences"), Map.of()), ResearchToolItemType.LATEX_OUTLINE,
                    new ResearchBudget(20, 200, 300, 2_000_000)),
            contract(PROJECT_BIBTEX_AUDIT, "Audit BibTeX entries, duplicates, required fields, and citation usage.",
                    policy(Set.of("relativePaths"), Set.of("relativePaths", "includeUnusedEntries"),
                            Map.of("relativePaths", 20), Map.of("relativePaths", 256), Map.of(), Set.of("relativePaths"),
                            Set.of("includeUnusedEntries"), Map.of()), ResearchToolItemType.BIBTEX_AUDIT,
                    new ResearchBudget(20, 300, 400, 2_000_000)),
            contract(PROJECT_CODE_SYMBOLS, "List code symbols, entry points, parameters, and dependency references.",
                    policy(Set.of("relativePaths"), Set.of("relativePaths", "symbolQuery", "includeDependencies"),
                            Map.of("relativePaths", 30), Map.of("relativePaths", 256), Map.of("symbolQuery", 160),
                            Set.of("relativePaths"), Set.of("includeDependencies"), Map.of()), ResearchToolItemType.CODE_SYMBOL,
                    new ResearchBudget(30, 500, 600, 3_000_000)),
            contract(PROJECT_EXPERIMENT_SUMMARY, "Summarize experiment configuration, CSV metrics, reports, and result files.",
                    policy(Set.of("relativePaths"), Set.of("relativePaths", "metricNames", "maxRowsPerFile"),
                            Map.of("relativePaths", 30, "metricNames", 30), Map.of("relativePaths", 256, "metricNames", 80),
                            Map.of(), Set.of("relativePaths"), Set.of(),
                            Map.of("maxRowsPerFile", new ResearchToolInputPolicy.IntegerRange(1, 500))),
                    ResearchToolItemType.EXPERIMENT_SUMMARY, new ResearchBudget(30, 300, 400, 5_000_000)),
            contract(PROJECT_CROSS_MATERIAL_SEARCH, "Find one concept across paper, code, configuration, and experiment material. "
                            + "For a large or unfamiliar Project, first call project_manifest, select concrete relevant files, "
                            + "then pass them in relativePaths; prefer maxMatches 10-20 to control input and result cost. "
                            + "Do not repeatedly search the entire Project after an input/result byte-budget error.",
                    policy(Set.of("query"), Set.of("query", "relativePaths", "maxMatches", "caseSensitive"),
                            Map.of("relativePaths", 50), Map.of("relativePaths", 256), Map.of("query", 200),
                            Set.of("relativePaths"), Set.of("caseSensitive"),
                            Map.of("maxMatches", new ResearchToolInputPolicy.IntegerRange(1, 100))),
                    ResearchToolItemType.CROSS_MATERIAL_LINK, new ResearchBudget(50, 100, 300, 5_000_000))
    );

    private ResearchToolContracts() {
    }

    public static List<ResearchToolContract> all() {
        return CONTRACTS;
    }

    public static ResearchToolContract byName(String name) {
        return CONTRACTS.stream().filter(contract -> contract.definition().name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown research tool contract: " + name));
    }

    private static ResearchToolContract contract(String name, String description, ResearchToolInputPolicy policy,
                                                 ResearchToolItemType itemType, ResearchBudget budget) {
        return new ResearchToolContract(new ToolDefinition(name, description, inputSchema(policy)), outputSchema(itemType),
                new ToolDescriptor(name, "v1", "scientific-project-read", List.of(ToolDescriptor.CapabilityProfile.PROJECT),
                        List.of("research:project-read"), List.of(ToolDescriptor.ResourceScope.PROJECT),
                        ToolDescriptor.SideEffectType.READ_ONLY, ToolDescriptor.ConfirmationPolicy.NEVER,
                        ToolDescriptor.AsyncMode.SYNC, ToolDescriptor.IdempotencyPolicy.NONE,
                        ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, false), policy, itemType, budget, "CONTRACT_ONLY");
    }

    private static ResearchToolInputPolicy policy(Set<String> required, Set<String> allowed, Map<String, Integer> arrays,
                                                   Map<String, Integer> arrayItems, Map<String, Integer> strings,
                                                   Set<String> paths, Set<String> booleans,
                                                   Map<String, ResearchToolInputPolicy.IntegerRange> integers) {
        return new ResearchToolInputPolicy(required, allowed, arrays, arrayItems, strings, paths, booleans, integers);
    }

    /** Package-visible so the multi-required-field schema regression can freeze this builder. */
    static ObjectNode inputSchema(ResearchToolInputPolicy policy) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        for (String field : policy.allowedFields()) {
            ObjectNode property = properties.putObject(field);
            if (policy.maxArrayItems().containsKey(field)) {
                property.put("type", "array");
                property.put("minItems", policy.requiredFields().contains(field) ? 1 : 0);
                property.put("maxItems", policy.maxArrayItems().get(field));
                ObjectNode item = property.putObject("items");
                item.put("type", "string");
                item.put("minLength", 1);
                item.put("maxLength", policy.maxArrayItemLengths().get(field));
                if (policy.relativePathArrayFields().contains(field)) {
                    item.put("description", "Normalized Project-relative path only; absolute paths, traversal, and surrounding whitespace are rejected.");
                }
            } else if (policy.maxStringLengths().containsKey(field)) {
                property.put("type", "string");
                property.put("minLength", 1);
                property.put("maxLength", policy.maxStringLengths().get(field));
            } else if (policy.booleanFields().contains(field)) {
                property.put("type", "boolean");
            } else if (policy.integerFields().containsKey(field)) {
                ResearchToolInputPolicy.IntegerRange range = policy.integerFields().get(field);
                property.put("type", "integer");
                property.put("minimum", range.min());
                property.put("maximum", range.max());
            }
        }
        ArrayNode required = schema.putArray("required");
        policy.requiredFields().stream().sorted().forEach(required::add);
        return schema;
    }

    private static ObjectNode outputSchema(ResearchToolItemType itemType) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object"); schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ObjectNode status = properties.putObject("status"); status.put("type", "string");
        ArrayNode statusValues = status.putArray("enum");
        for (ResearchToolResultState value : ResearchToolResultState.values()) statusValues.add(value.name());
        ObjectNode items = properties.putObject("items"); items.put("type", "array");
        if (itemType == ResearchToolItemType.CROSS_MATERIAL_LINK) {
            ObjectNode alternatives = JsonNodeFactory.instance.objectNode();
            alternatives.putArray("oneOf").add(itemSchema(ResearchToolItemType.CROSS_MATERIAL_LINK))
                    .add(itemSchema(ResearchToolItemType.LITERAL_MATCH));
            items.set("items", alternatives);
        } else items.set("items", itemSchema(itemType));
        properties.putObject("evidenceRefs").put("type", "array").set("items", evidenceSchema());
        properties.putObject("partial").put("type", "boolean");
        properties.putObject("truncated").put("type", "boolean");
        properties.putObject("parseFailed").put("type", "boolean");
        ObjectNode error = properties.putObject("errorCode"); error.put("type", "string");
        ArrayNode errors = error.putArray("enum");
        for (ResearchToolErrorCode value : ResearchToolErrorCode.values()) errors.add(value.name());
        ArrayNode required = schema.putArray("required");
        List.of("status", "items", "evidenceRefs", "partial", "truncated", "parseFailed").forEach(required::add);
        return schema;
    }

    private static ObjectNode itemSchema(ResearchToolItemType type) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode(); schema.put("type", "object"); schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties"); ArrayNode required = schema.putArray("required");
        switch (type) {
            case LATEX_OUTLINE -> { strings(properties, required, "kind", "detail"); optionalString(properties, "identifier"); }
            case BIBTEX_AUDIT -> { strings(properties, required, "issue", "detail"); optionalString(properties, "citationKey"); }
            case CODE_SYMBOL -> { strings(properties, required, "kind", "qualifiedName"); optionalString(properties, "dependencyReference"); }
            case EXPERIMENT_SUMMARY -> { strings(properties, required, "assetType", "value"); optionalString(properties, "metricName"); }
            case CROSS_MATERIAL_LINK -> {
                strings(properties, required, "concept", "relation");
                ObjectNode linked = properties.putObject("linkedEvidence"); linked.put("type", "array"); linked.put("minItems", 2); linked.set("items", evidenceSchema());
                required.add("linkedEvidence");
            }
            case LITERAL_MATCH -> {
                strings(properties, required, "query");
                properties.putObject("relativePath").put("type", "string").put("minLength", 1);
                properties.putObject("lineNumber").put("type", "integer").put("minimum", 1);
                required.add("relativePath").add("lineNumber");
            }
        }
        properties.set("content", untrustedContentSchema()); required.add("content");
        return schema;
    }

    private static void strings(ObjectNode properties, ArrayNode required, String... names) {
        for (String name : names) { properties.putObject(name).put("type", "string").put("minLength", 1); required.add(name); }
    }

    private static void optionalString(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "string").put("minLength", 1);
    }

    private static ObjectNode untrustedContentSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode(); schema.put("type", "object"); schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties"); properties.putObject("text").put("type", "string");
        ObjectNode trust = properties.putObject("trustLabel"); trust.put("type", "string"); trust.putArray("enum").add(TrustLabel.UNTRUSTED_PROJECT_CONTENT.name());
        properties.set("evidence", evidenceSchema());
        schema.putArray("required").add("text").add("evidence").add("trustLabel");
        return schema;
    }

    private static ObjectNode evidenceSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode(); schema.put("type", "object"); schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        ObjectNode version = properties.putObject("projectVersion"); version.put("type", "string");
        ArrayNode versionForms = version.putArray("oneOf");
        versionForms.addObject().put("pattern", ProjectVersionRef.SHA256_PATTERN);
        versionForms.addObject().put("pattern", ProjectVersionRef.NAMESPACED_TOKEN_PATTERN);
        properties.putObject("relativePath").put("type", "string").put("minLength", 1);
        properties.putObject("fileHash").put("type", "string").put("pattern", "^[a-f0-9]{64}$");
        properties.putObject("parserVersion").put("type", "string").put("pattern", ParserVersionRef.PATTERN);
        ObjectNode trust = properties.putObject("trustLabel"); trust.put("type", "string");
        ArrayNode trustLabels = trust.putArray("enum"); for (TrustLabel label : TrustLabel.values()) trustLabels.add(label.name());
        required.add("projectVersion").add("relativePath").add("fileHash").add("parserVersion").add("trustLabel");
        ObjectNode range = properties.putObject("range"); range.put("type", "object"); range.put("additionalProperties", false);
        ObjectNode rangeProperties = range.putObject("properties"); rangeProperties.putObject("startLine").put("type", "integer").put("minimum", 1); rangeProperties.putObject("endLine").put("type", "integer").put("minimum", 1);
        range.putArray("required").add("startLine").add("endLine");
        required.add("range");
        return schema;
    }
}
