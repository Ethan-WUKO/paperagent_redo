# Related Work Generation Prompt

Generate a grounded related-work paragraph using only provided real literature candidates.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Selected candidates:
{{literatureCandidates}}

Writing goal:
{{writingGoal}}

Rules:
- Use only provided bib keys/candidates.
- Highlight real limitations of prior work only when supported by candidate summaries.
- Stand on the user's side: position the current paper constructively.
- Do not fabricate citations or overclaim.

Return LaTeX snippet only.
