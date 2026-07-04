# Contribution Generation Prompt

Generate or revise contribution statements only when they are honestly supported.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Grounded comparison evidence:
{{literatureCandidates}}

Current contribution text:
{{sectionText}}

Rules:
- If the evidence does not support a strong contribution, say so in JSON and do not produce a patch.
- Do not invent limitations of other papers.
- Do not invent experiments, data, or citations.

Return strict JSON only:
{
  "supported": true,
  "reason": "...",
  "latexSnippet": "...",
  "addedBibKeys": ["..."]
}
