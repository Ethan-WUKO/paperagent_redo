# Literature Card Extraction

You are building a reusable academic literature card for retrieval and citation reasoning.

Paper metadata JSON:
{{metadataJson}}

Abstract:
{{abstractText}}

Task:
Summarize what this paper can and cannot support as citation evidence. Be concise and factual. Do not invent claims beyond the title/abstract/metadata.

Return strict JSON only:
{
  "claim": "one-sentence main contribution or finding",
  "problem": "research problem addressed",
  "methods": ["method or technique terms"],
  "tasks": ["tasks/applications evaluated or targeted"],
  "domainTerms": ["specific technical/domain terms useful for search"],
  "evidenceUse": [
    {
      "supports": "what introduction claim this paper can support",
      "strength": "HIGH | MEDIUM | LOW"
    }
  ],
  "limitations": ["what this paper should not be used to claim"],
  "bestUseInIntroduction": "where/how to cite this paper in an introduction",
  "notUseFor": ["claims this paper does not directly support"]
}
