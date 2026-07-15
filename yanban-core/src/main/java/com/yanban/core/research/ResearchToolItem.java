package com.yanban.core.research;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** A closed, typed output item; every item contains untrusted content with mandatory provenance. */
public sealed interface ResearchToolItem permits LatexOutlineItem, BibtexAuditItem, CodeSymbolItem,
        ExperimentSummaryItem, CrossMaterialLinkItem, LiteralMatchItem {

    @JsonIgnore
    ResearchToolItemType itemType();

    UntrustedResearchContent content();
}
