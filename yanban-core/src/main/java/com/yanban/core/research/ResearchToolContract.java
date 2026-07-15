package com.yanban.core.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import java.util.HashSet;
import java.util.Set;

/** Declarative tool contract only. It intentionally contains neither an executor nor registry integration. */
public record ResearchToolContract(ToolDefinition definition, JsonNode outputSchema, ToolDescriptor descriptor,
                                   ResearchToolInputPolicy inputPolicy, ResearchToolItemType itemType, ResearchBudget budget,
                                   String contractStatus) {
    public ResearchToolContract {
        if (definition == null || outputSchema == null || descriptor == null || inputPolicy == null || itemType == null || budget == null
                || contractStatus == null || !contractStatus.equals("CONTRACT_ONLY")) {
            throw new IllegalArgumentException("research tools must be complete CONTRACT_ONLY declarations");
        }
        if (!definition.name().equals(descriptor.name()) || descriptor.sideEffectType() != ToolDescriptor.SideEffectType.READ_ONLY
                || descriptor.modelVisible()) {
            throw new IllegalArgumentException("research tool contract must be explicit READ_ONLY and not model-exposed");
        }
    }

    /** Validates model arguments before using them in the stable server-scoped call key. */
    public ResearchCallKey callKey(ProjectVersionRef projectVersion, JsonNode modelArguments) {
        inputPolicy.validate(modelArguments);
        return ResearchCallKey.of(definition.name(), projectVersion, modelArguments);
    }

    /** Contract-only type/budget validation for a future executor result. */
    public void validateOutcome(ResearchToolOutcome outcome) {
        if (outcome == null || outcome.items().stream().anyMatch(item -> !accepts(item.itemType()))) {
            throw new ResearchContractException(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE,
                    "outcome item type does not match tool contract");
        }
        Set<ResearchEvidenceRef> envelope = new HashSet<>(outcome.evidenceRefs());
        for (ResearchToolItem item : outcome.items()) {
            if (!envelope.contains(item.content().evidence())) {
                throw new ResearchContractException(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE,
                        "outcome evidence must include every item content provenance");
            }
            if (item instanceof CrossMaterialLinkItem cross && !envelope.containsAll(cross.linkedEvidence())) {
                throw new ResearchContractException(ResearchToolErrorCode.INTERNAL_CONTRACT_FAILURE,
                        "outcome evidence must include every cross-material provenance");
            }
        }
        budget.validate(outcome.budgetUsage());
    }

    private boolean accepts(ResearchToolItemType actual) {
        return actual == itemType || (itemType == ResearchToolItemType.CROSS_MATERIAL_LINK
                && actual == ResearchToolItemType.LITERAL_MATCH);
    }
}
