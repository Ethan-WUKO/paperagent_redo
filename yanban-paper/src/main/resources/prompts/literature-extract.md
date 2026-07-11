# Literature Extract Prompt

Extract reusable literature-card analysis from a real candidate paper abstract.
This paper was returned by a literature API; do not invent fields not supported by title/abstract.

Candidate title: {{candidateTitle}}
Authors/year/venue: {{candidateMeta}}
Abstract:
{{candidateAbstract}}

Return strict JSON only:
{
  "problem": "...",
  "method": "...",
  "datasets": ["..."],
  "baselines": ["..."],
  "metrics": ["..."],
  "contributions": ["..."],
  "tasks": ["..."],
  "keywords": ["..."],
  "role": "background"
}

Role must be one of: background, competitor, baseline.
