# Gap Analysis Prompt

You generate grounded paper improvement suggestions.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Introduction analysis, citation slots, and citation audit:
{{conceptLadder}}

Selected literature cards grouped by citation-slot query:
{{literatureCandidates}}

Paper structure and detected issues:
{{structureSummary}}

Rules:
- Every evidence item must reference a real candidate card id from the provided list.
- Keep each statement concise (at most 45 words).
- Include every candidate card whose stored abstract or literature-card analysis directly or complementarily supports the target slot. Do not impose an arbitrary evidence-count limit.
- Exclude merely topical, contradictory, duplicate, or metadata-unsafe candidates instead of citing them as filler.
- Recommendations must be tied to Introduction citation slots when possible.
- Preserve user's correct existing citations and correct claims; only flag or revise weak/mismatched/missing citation support.
- Do not invent papers, citations, experiments, datasets, or numeric results.
- Every grounded ADVOCACY/RelatedWork suggestion must select exactly one existing citation slot using `targetSlotId`.
- The backend constructs and validates the LaTeX citation patch from that slot. Do not invent an anchor, BibTeX key, or `applicable` decision.
- CRITIQUE items about a supplied citation slot must also identify that slot with `targetSlotId`. Use an empty `targetSlotId` only for genuinely paper-wide issues.
- Experiment/data additions without actual results must be B-class skeletons only.
- If the user's contribution cannot be honestly supported, convert it into critique.

Return strict JSON only:
{
  "suggestions": [
    {
      "track": "ADVOCACY",
      "category": "RelatedWork",
      "severity": "minor",
      "statement": "...",
      "evidence": ["card-id"],
      "targetSlotId": "slot-8"
    }
  ]
}
