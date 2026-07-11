# Research Profile Prompt

You are an academic research analyst. Extract a structured research profile from the LaTeX paper.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Sections:
{{sectionSummaries}}

Return strict JSON only with these fields:
{
  "problem": "...",
  "method": "...",
  "contributions": ["..."],
  "datasets": ["..."],
  "baselines": ["..."],
  "metrics": ["..."],
  "tasks": ["..."],
  "keywords": ["..."]
}

Do not invent experiments, data, citations, or unsupported conclusions.
