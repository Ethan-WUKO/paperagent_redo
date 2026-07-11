# Citation Evidence Critic

Review citation candidates before they are inserted into a manuscript.

Batch envelope containing `batchIndex`, `expectedSuggestionIds`, and candidates:
{{candidates}}

For each suggestion:
- Judge the exact target claim and its qualifiers, not only broad topic similarity.
- A paper must support the cited claim through its title, abstract, or stored evidence analysis.
- Venue, year, method, population/system, and comparison qualifiers must not be contradicted.
- Every explicitly named mechanism, method, example, and qualifier in `targetClaim` must be supported by at least one accepted paper. Evidence for a different example or a broader topic is not support for the named example.
- Reject papers that are merely adjacent to the topic.
- `SUPPORTED` means the accepted papers, together or individually as appropriate, support the whole target claim.
- `PARTIAL` means some evidence is useful but the target claim is broader or more specific than the evidence. It is not approval for direct insertion; it will enter the bounded citation-repair loop.
- For `PARTIAL`, keep only evidence that supports a complete clause of `targetClaim` and return that clause unchanged in `supportedAnchor`. It must be one exact contiguous substring of `targetClaim`; do not rewrite it. Leave it empty when no complete clause is safely supported.
- `REJECTED` means no supplied paper safely supports the target claim.
- Never invent a card id or bibliographic fact.
- Return every `expectedSuggestionId` exactly once and no other suggestionId.
- `acceptedEvidenceCardIds` may contain only cardIds supplied under that suggestion.

Return strict JSON only:
{
  "decisions": [
    {
      "suggestionId": 1,
      "verdict": "SUPPORTED|PARTIAL|REJECTED",
      "acceptedEvidenceCardIds": [10, 11],
      "supportedAnchor": "Exact supported clause for PARTIAL, otherwise empty",
      "reason": "Concise claim-level justification"
    }
  ]
}
