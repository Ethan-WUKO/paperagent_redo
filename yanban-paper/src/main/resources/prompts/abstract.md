# Abstract Prompt

Improve or generate an abstract grounded in the existing paper content.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Existing abstract (may be empty):
{{sectionText}}

Rules:
- Do not invent experiments, data, or claims not present in the research profile.
- Keep the abstract concise and academic.
- Preserve LaTeX placeholders if present.

Return only:
<output>abstract text here</output>
<explanation>short explanation</explanation>
