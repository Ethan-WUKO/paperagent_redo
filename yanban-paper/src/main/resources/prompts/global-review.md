# Whole-Paper Consistency Review

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}

Research profile:
{{researchProfile}}

Introduction and retrieval analysis:
{{conceptLadder}}

Gap analysis:
{{gapMatrix}}

Section dossier (ordered, compact excerpts):
{{sectionDossier}}

Technical fact index copied from the final polished draft:
{{technicalFacts}}

Review the paper as a whole. Check only cross-section concerns: transition and narrative flow, logical dependencies, terminology and symbol consistency, formula explanation versus surrounding prose, claim-method-result-conclusion alignment, and duplication.

Rules:
- Do not rewrite any section.
- Do not propose or invent equations, results, citations, experiments, or claims.
- Formula issues are report-only and must have autoFixAllowed=false.
- Review only the final polished draft represented above, not assumptions about an earlier manuscript version.
- Every issue must include one or more short evidence objects whose `quote` is copied verbatim from the supplied draft or technical fact index.
- A NOTATION dimension conflict requires evidence for both the symbol declaration and the expression whose dimensions conflict.
- Equation-ending periods and commas are normal mathematical punctuation. Never report them as LaTeX errors.
- Do not report a compilation or syntax error unless it is explicitly present in the supplied diagnostics.
- Use section orderIndex values from the dossier when identifying sections.
- Return strict JSON only.

Return:
{
  "issues": [
    {
      "type": "TRANSITION|LOGIC|NOTATION|FORMULA|CLAIM_RESULT_MISMATCH|DUPLICATION",
      "ruleId": "DIMENSION_CONFLICT|SYMBOL_CONFLICT|TERM_CONFLICT|FORMULA_PROSE_MISMATCH|UNDEFINED_SYMBOL|EQUATION_REFERENCE_MISMATCH|NARRATIVE_GAP|CLAIM_MISMATCH|DUPLICATED_CONTENT",
      "sectionIds": [1, 2],
      "severity": "minor|major|blocker",
      "message": "...",
      "suggestedFix": "...",
      "autoFixAllowed": false,
      "evidence": [
        {
          "sectionOrder": 1,
          "equationLabel": "optional-equation-label",
          "quote": "Short exact quote copied from the final draft"
        }
      ]
    }
  ]
}
