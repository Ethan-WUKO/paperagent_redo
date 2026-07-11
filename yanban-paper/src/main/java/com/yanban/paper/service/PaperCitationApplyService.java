package com.yanban.paper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.domain.Suggestion;
import com.yanban.paper.latex.LatexBibEntry;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Rebuilds citation material from the immutable uploaded bibliography and the
 * current accepted suggestions. Model supplied BibTeX keys are deliberately
 * ignored because they are not a stable identity boundary.
 */
@Service
public class PaperCitationApplyService {

    private static final Pattern CITE_COMMAND = Pattern.compile("\\\\(?:cite|citep|citet|parencite|textcite|autocite)\\*?(?:\\s*\\[[^]]*]){0,2}\\s*\\{([^{}]+)}");
    private static final Pattern CITATION_SLOT = Pattern.compile("\\\\yanbancitationslot\\s*\\{\\d+}");
    private static final Pattern SAFE_ANCHOR = Pattern.compile("(?s).{8,}");
    private static final Pattern SECTION_COMMAND = Pattern.compile("\\\\(?:section|subsection|subsubsection)\\*?\\s*\\{([^{}]+)}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double FUZZY_MATCH_THRESHOLD = 0.92;
    private static final double FUZZY_MATCH_MARGIN = 0.05;

    private final ObjectMapper objectMapper;

    public PaperCitationApplyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CitationApplyResult apply(String polishedTex,
                                     String originalBib,
                                     String bibFilename,
                                     Map<String, LatexBibEntry> existingBibliography,
                                     List<Suggestion> suggestions,
                                     Map<Long, List<LiteratureCard>> evidenceCards) {
        String baselineTex = polishedTex == null ? "" : polishedTex;
        String baselineBib = originalBib == null ? "" : originalBib;
        Set<String> usedKeys = new LinkedHashSet<>(existingBibliography == null ? Set.of() : existingBibliography.keySet());
        Map<String, String> existingByDoi = new LinkedHashMap<>();
        Map<String, String> existingByTitle = new LinkedHashMap<>();
        if (existingBibliography != null) {
            existingBibliography.forEach((key, entry) -> {
                Map<String, String> fields = entry.fields() == null ? Map.of() : entry.fields();
                String doi = normalizeDoi(fields.get("doi"));
                if (!doi.isBlank()) existingByDoi.putIfAbsent(doi, key);
                String title = titleFingerprint(fields.get("title"));
                if (!title.isBlank()) existingByTitle.putIfAbsent(title, key);
            });
        }

        Map<Long, List<LiteratureCard>> accepted = new LinkedHashMap<>();
        if (suggestions != null) {
            for (Suggestion suggestion : suggestions) {
                if (!"ACCEPTED".equalsIgnoreCase(suggestion.getStatus())
                        || !Boolean.TRUE.equals(suggestion.getApplicable())
                        || evidenceCards == null
                        || evidenceCards.getOrDefault(suggestion.getId(), List.of()).isEmpty()) {
                    continue;
                }
                accepted.put(suggestion.getId(), evidenceCards.getOrDefault(suggestion.getId(), List.of()));
            }
        }

        Map<Long, List<String>> keysBySuggestion = new LinkedHashMap<>();
        Map<String, CardProvenance> newEntries = new LinkedHashMap<>();
        Map<String, String> resolvedNewKeys = new LinkedHashMap<>();
        List<Map<String, Object>> applied = new ArrayList<>();
        List<Map<String, Object>> manual = new ArrayList<>();
        Set<Long> appliedSuggestionIds = new LinkedHashSet<>();
        String patchedTex = baselineTex;

        for (Map.Entry<Long, List<LiteratureCard>> acceptedEntry : accepted.entrySet()) {
            Long suggestionId = acceptedEntry.getKey();
            List<String> keys = new ArrayList<>();
            for (LiteratureCard card : acceptedEntry.getValue()) {
                String identity = cardIdentity(card);
                String key = resolvedNewKeys.get(identity);
                if (key == null) {
                    key = resolveKey(card, existingByDoi, existingByTitle, usedKeys);
                    if (!existingByDoi.containsValue(key) && !existingByTitle.containsValue(key)) {
                        resolvedNewKeys.put(identity, key);
                    }
                }
                keys.add(key);
                if (!existingByDoi.containsValue(key) && !existingByTitle.containsValue(key)) {
                    newEntries.putIfAbsent(key, new CardProvenance(card, new LinkedHashSet<>()));
                }
                CardProvenance provenance = newEntries.get(key);
                if (provenance != null) provenance.suggestionIds().add(suggestionId);
            }
            keysBySuggestion.put(suggestionId, new ArrayList<>(new LinkedHashSet<>(keys)));
        }

        if (suggestions != null) {
            List<Suggestion> applicationOrder = new ArrayList<>(suggestions);
            applicationOrder.sort((left, right) -> Boolean.compare(hasSupportedClosure(left), hasSupportedClosure(right)));
            for (Suggestion suggestion : applicationOrder) {
                List<String> keys = keysBySuggestion.get(suggestion.getId());
                if (keys == null || keys.isEmpty()) continue;
                Map<String, Object> patch = parsePatch(suggestion.getPatchJson());
                Map<String, Object> closure = mapValue(patch.get("citationClosure"));
                if ("SUPPORTED".equalsIgnoreCase(stringValue(closure.get("status")))) {
                    ClosurePatchResult closureResult = applyClosurePatch(patchedTex, patch, closure, keys);
                    if (!closureResult.applied()) {
                        manual.add(status(suggestion, keys, closureResult.status(), closureResult.message()));
                        continue;
                    }
                    patchedTex = closureResult.text();
                    Map<String, Object> appliedStatus = status(
                            suggestion,
                            keys,
                            "APPLIED",
                            "Citation closure applied one verified local Introduction patch and its citation atomically.");
                    appliedStatus.put("matchMode", "CLOSURE_" + stringValue(closure.get("operation")));
                    applied.add(appliedStatus);
                    appliedSuggestionIds.add(suggestion.getId());
                    continue;
                }
                String anchor = stringValue(patch.get("anchor"));
                if (anchor.isBlank()) {
                    manual.add(status(suggestion, keys, "NO_STABLE_ANCHOR", "No exact citation anchor was supplied."));
                    continue;
                }
                if (anchor.contains(":end") || anchor.contains(":start") || !SAFE_ANCHOR.matcher(anchor).matches()) {
                    manual.add(status(suggestion, keys, "UNSAFE_ANCHOR", "Section-relative or short anchors are not unique enough for automatic citation insertion."));
                    continue;
                }
                String citation = "\\cite{" + String.join(",", keys) + "}";
                String slotMarker = citationSlotMarker(suggestion.getId());
                List<Integer> markerMatches = occurrences(patchedTex, slotMarker, 0, patchedTex.length());
                if (markerMatches.size() == 1) {
                    int markerStart = markerMatches.get(0);
                    SlotReplacement replacement = replaceProtectedSlot(patchedTex, markerStart, slotMarker.length(), keys);
                    patchedTex = replacement.text();
                    Map<String, Object> appliedStatus = status(suggestion, keys, "APPLIED", "Citation inserted at its protected citation-slot marker.");
                    appliedStatus.put("matchMode", replacement.matchMode());
                    applied.add(appliedStatus);
                    appliedSuggestionIds.add(suggestion.getId());
                    continue;
                }
                if (markerMatches.size() > 1) {
                    manual.add(status(suggestion, keys, "SLOT_MARKER_AMBIGUOUS", "The protected citation-slot marker occurs more than once; no text was changed."));
                    continue;
                }
                AnchorMatch match = findAnchorMatch(patchedTex, anchor, patch);
                if (!match.matched()) {
                    manual.add(status(suggestion, keys, match.status(), match.message()));
                    continue;
                }
                String matchedText = patchedTex.substring(match.start(), match.end());
                String replacement = appendCitation(matchedText, citation);
                patchedTex = patchedTex.substring(0, match.start()) + replacement + patchedTex.substring(match.end());
                Map<String, Object> appliedStatus = status(suggestion, keys, "APPLIED", "Citation inserted at a unique verified manuscript anchor.");
                appliedStatus.put("matchMode", match.mode());
                applied.add(appliedStatus);
                appliedSuggestionIds.add(suggestion.getId());
            }
        }

        patchedTex = CITATION_SLOT.matcher(patchedTex).replaceAll("");

        newEntries.entrySet().removeIf(entry -> entry.getValue().suggestionIds().stream().noneMatch(appliedSuggestionIds::contains));

        StringBuilder mergedBib = new StringBuilder(baselineBib);
        if (!newEntries.isEmpty() && mergedBib.length() > 0 && !mergedBib.toString().endsWith("\n")) mergedBib.append('\n');
        StringBuilder novelBib = new StringBuilder();
        for (Map.Entry<String, CardProvenance> entry : newEntries.entrySet()) {
            String block = provenanceBlock(entry.getKey(), entry.getValue());
            mergedBib.append(block);
            novelBib.append(block);
        }

        List<Map<String, Object>> bibliography = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : keysBySuggestion.entrySet()) {
            for (String key : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("suggestionId", entry.getKey());
                item.put("bibKey", key);
                item.put("alreadyPresent", !newEntries.containsKey(key));
                bibliography.add(item);
            }
        }
        return new CitationApplyResult(
                patchedTex,
                mergedBib.toString(),
                novelBib.toString(),
                newEntries.values().stream().map(item -> cardSummary(item.card())).toList(),
                bibliography,
                applied,
                manual,
                existingBibliography == null ? 0 : existingBibliography.size(),
                newEntries.keySet(),
                normalizeFilename(bibFilename)
        );
    }

    private ClosurePatchResult applyClosurePatch(String tex,
                                                 Map<String, Object> patch,
                                                 Map<String, Object> closure,
                                                 List<String> keys) {
        String originalAnchor = stringValue(closure.get("originalAnchor"));
        String replacementText = stringValue(closure.get("replacementText"));
        String citationAnchor = stringValue(closure.get("citationAnchor"));
        if (originalAnchor.isBlank() || replacementText.isBlank() || citationAnchor.isBlank()) {
            return ClosurePatchResult.failed(
                    "CLOSURE_PATCH_INCOMPLETE",
                    "The approved citation closure is missing its original anchor, replacement, or citation anchor.");
        }

        AnchorMatch original = findExactOrNormalizedAnchor(tex, originalAnchor, patch);
        if (!original.matched()) {
            return ClosurePatchResult.failed(original.status(), original.message());
        }
        String candidate = tex.substring(0, original.start())
                + replacementText
                + tex.substring(original.end());
        AnchorMatch citationTarget = findExactOrNormalizedAnchor(candidate, citationAnchor, patch);
        int replacementStart = original.start();
        int replacementEnd = replacementStart + replacementText.length();
        if (!citationTarget.matched()
                || citationTarget.start() < replacementStart
                || citationTarget.end() > replacementEnd) {
            return ClosurePatchResult.failed(
                    "CLOSURE_CITATION_ANCHOR_NOT_FOUND",
                    "The approved local replacement could not be paired with one unique citation anchor; no text was changed.");
        }
        String matchedText = candidate.substring(citationTarget.start(), citationTarget.end());
        String citation = "\\cite{" + String.join(",", keys) + "}";
        String citedText = appendCitation(matchedText, citation);
        return ClosurePatchResult.applied(
                candidate.substring(0, citationTarget.start())
                        + citedText
                        + candidate.substring(citationTarget.end()));
    }

    private AnchorMatch findExactOrNormalizedAnchor(String tex,
                                                    String anchor,
                                                    Map<String, Object> patch) {
        Scope scope = resolveScope(tex, patch);
        List<Integer> exact = occurrences(tex, anchor, scope.start(), scope.end());
        if (exact.size() == 1) {
            int start = exact.get(0);
            return AnchorMatch.matched(start, start + anchor.length(), "EXACT");
        }
        if (exact.size() > 1) {
            return AnchorMatch.failed(
                    "CLOSURE_ANCHOR_AMBIGUOUS",
                    "The approved citation-closure anchor occurs more than once; no text was changed.");
        }

        String normalizedAnchor = normalizeForMatch(anchor);
        if (normalizedAnchor.isBlank()) {
            return AnchorMatch.failed(
                    "CLOSURE_ANCHOR_NOT_FOUND",
                    "The approved citation-closure anchor was not found; no text was changed.");
        }
        NormalizedText normalizedScope = normalizeWithOffsets(
                tex.substring(scope.start(), scope.end()), scope.start());
        List<Integer> normalizedMatches = occurrences(
                normalizedScope.text(), normalizedAnchor, 0, normalizedScope.text().length());
        if (normalizedMatches.size() != 1) {
            return AnchorMatch.failed(
                    normalizedMatches.isEmpty() ? "CLOSURE_ANCHOR_NOT_FOUND" : "CLOSURE_ANCHOR_AMBIGUOUS",
                    normalizedMatches.isEmpty()
                            ? "The approved citation-closure anchor was not found; no text was changed."
                            : "The normalized citation-closure anchor is not unique; no text was changed.");
        }
        int normalizedStart = normalizedMatches.get(0);
        int normalizedEnd = normalizedStart + normalizedAnchor.length() - 1;
        return AnchorMatch.matched(
                normalizedScope.offsets().get(normalizedStart),
                normalizedScope.offsets().get(normalizedEnd) + 1,
                "NORMALIZED");
    }

    private String citationSlotMarker(Long suggestionId) {
        return "\\yanbancitationslot{" + suggestionId + "}";
    }

    private SlotReplacement replaceProtectedSlot(String tex,
                                                  int markerStart,
                                                  int markerLength,
                                                  List<String> requestedKeys) {
        int previousEnd = markerStart;
        while (previousEnd > 0 && Character.isWhitespace(tex.charAt(previousEnd - 1))) previousEnd--;
        Matcher matcher = CITE_COMMAND.matcher(tex);
        CitationMatch adjacent = null;
        while (matcher.find() && matcher.end() <= previousEnd) {
            if (matcher.end() == previousEnd) {
                adjacent = new CitationMatch(matcher.start(1), matcher.end(1), matcher.end(), matcher.group(1));
            }
        }
        if (adjacent != null) {
            String merged = mergeKeys(adjacent.keys(), requestedKeys);
            String updated = tex.substring(0, adjacent.keyStart())
                    + merged
                    + tex.substring(adjacent.keyEnd(), adjacent.commandEnd())
                    + tex.substring(markerStart + markerLength);
            return new SlotReplacement(updated, "PROTECTED_SLOT_MERGED");
        }
        String citation = "\\cite{" + String.join(",", new LinkedHashSet<>(requestedKeys)) + "}";
        return new SlotReplacement(
                tex.substring(0, markerStart) + citation + tex.substring(markerStart + markerLength),
                "PROTECTED_SLOT");
    }

    private String mergeKeys(String existing, List<String> requested) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : existing.split(",")) {
            if (!key.isBlank()) keys.add(key.trim());
        }
        for (String key : requested) {
            if (key != null && !key.isBlank()) keys.add(key.trim());
        }
        return String.join(",", keys);
    }

    private String resolveKey(LiteratureCard card,
                              Map<String, String> existingByDoi,
                              Map<String, String> existingByTitle,
                              Set<String> usedKeys) {
        String doi = normalizeDoi(card.getDoi());
        if (!doi.isBlank() && existingByDoi.containsKey(doi)) return existingByDoi.get(doi);
        String title = titleFingerprint(card.getTitle());
        if (!title.isBlank() && existingByTitle.containsKey(title)) return existingByTitle.get(title);
        String base = "yb_" + slug(firstAuthor(card)) + "_" + (card.getPublicationYear() == null ? "nd" : card.getPublicationYear());
        if (base.equals("yb_paper_nd")) base = "yb_reference_nd";
        String key = base;
        int suffix = 2;
        while (usedKeys.contains(key)) key = base + "_" + suffix++;
        usedKeys.add(key);
        return key;
    }

    private String appendCitation(String anchor, String citation) {
        Matcher matcher = CITE_COMMAND.matcher(anchor);
        if (!matcher.find()) {
            int insertion = anchor.length();
            while (insertion > 0 && Character.isWhitespace(anchor.charAt(insertion - 1))) insertion--;
            if (insertion > 0 && ".,;:!?".indexOf(anchor.charAt(insertion - 1)) >= 0) insertion--;
            return anchor.substring(0, insertion) + " " + citation + anchor.substring(insertion);
        }
        Set<String> mergedKeys = new LinkedHashSet<>();
        for (String key : matcher.group(1).split(",")) {
            if (!key.isBlank()) mergedKeys.add(key.trim());
        }
        String requested = citation.substring(citation.indexOf('{') + 1, citation.length() - 1);
        for (String key : requested.split(",")) {
            if (!key.isBlank()) mergedKeys.add(key.trim());
        }
        String merged = String.join(",", mergedKeys);
        return anchor.substring(0, matcher.start(1)) + merged + anchor.substring(matcher.end(1));
    }

    private AnchorMatch findAnchorMatch(String tex, String anchor, Map<String, Object> patch) {
        Scope scope = resolveScope(tex, patch);
        List<Integer> exact = occurrences(tex, anchor, scope.start(), scope.end());
        if (exact.size() == 1) {
            int start = exact.get(0);
            return AnchorMatch.matched(start, start + anchor.length(), "EXACT");
        }
        if (exact.size() > 1) {
            return AnchorMatch.failed("ANCHOR_AMBIGUOUS", "The exact anchor occurs more than once in the target section; no text was changed.");
        }

        String normalizedAnchor = normalizeForMatch(anchor);
        if (!normalizedAnchor.isBlank()) {
            NormalizedText normalizedScope = normalizeWithOffsets(tex.substring(scope.start(), scope.end()), scope.start());
            List<Integer> normalizedMatches = occurrences(normalizedScope.text(), normalizedAnchor, 0, normalizedScope.text().length());
            if (normalizedMatches.size() == 1) {
                int normalizedStart = normalizedMatches.get(0);
                int normalizedEnd = normalizedStart + normalizedAnchor.length() - 1;
                return AnchorMatch.matched(normalizedScope.offsets().get(normalizedStart), normalizedScope.offsets().get(normalizedEnd) + 1, "NORMALIZED");
            }
            if (normalizedMatches.size() > 1) {
                return AnchorMatch.failed("ANCHOR_AMBIGUOUS", "The normalized anchor occurs more than once in the target section; no text was changed.");
            }
        }

        List<String> anchorWords = words(anchor);
        if (anchorWords.size() < 8) {
            return AnchorMatch.failed("ANCHOR_NOT_FOUND", "The anchor was not found and is too short for safe similarity matching.");
        }
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        String scopedText = tex.substring(scope.start(), scope.end());
        iterator.setText(scopedText);
        List<ScoredSpan> candidates = new ArrayList<>();
        int sentenceStart = iterator.first();
        for (int sentenceEnd = iterator.next(); sentenceEnd != BreakIterator.DONE; sentenceStart = sentenceEnd, sentenceEnd = iterator.next()) {
            int trimmedStart = sentenceStart;
            int trimmedEnd = sentenceEnd;
            while (trimmedStart < trimmedEnd && Character.isWhitespace(scopedText.charAt(trimmedStart))) trimmedStart++;
            while (trimmedEnd > trimmedStart && Character.isWhitespace(scopedText.charAt(trimmedEnd - 1))) trimmedEnd--;
            if (trimmedEnd <= trimmedStart) continue;
            double score = tokenDice(anchorWords, words(scopedText.substring(trimmedStart, trimmedEnd)));
            candidates.add(new ScoredSpan(scope.start() + trimmedStart, scope.start() + trimmedEnd, score));
        }
        candidates.sort((left, right) -> Double.compare(right.score(), left.score()));
        if (candidates.isEmpty() || candidates.get(0).score() < FUZZY_MATCH_THRESHOLD) {
            return AnchorMatch.failed("ANCHOR_NOT_FOUND", "No unique high-confidence sentence match was found in the target section.");
        }
        double secondScore = candidates.size() > 1 ? candidates.get(1).score() : -1.0;
        if (secondScore >= 0 && candidates.get(0).score() - secondScore < FUZZY_MATCH_MARGIN) {
            return AnchorMatch.failed("ANCHOR_AMBIGUOUS", "Multiple sentences are similarly close to the citation anchor; no text was changed.");
        }
        ScoredSpan best = candidates.get(0);
        return AnchorMatch.matched(best.start(), best.end(), "FUZZY");
    }

    private Scope resolveScope(String tex, Map<String, Object> patch) {
        List<SectionSpan> sections = new ArrayList<>();
        Matcher matcher = SECTION_COMMAND.matcher(tex);
        while (matcher.find()) sections.add(new SectionSpan(matcher.start(), matcher.end(), tex.length(), matcher.group(1)));
        for (int index = 0; index + 1 < sections.size(); index++) {
            SectionSpan current = sections.get(index);
            sections.set(index, new SectionSpan(current.commandStart(), current.contentStart(), sections.get(index + 1).commandStart(), current.title()));
        }
        Integer requestedOrder = integerValue(patch.get("sectionOrder"));
        if (requestedOrder != null && requestedOrder >= 0 && requestedOrder < sections.size()) {
            SectionSpan section = sections.get(requestedOrder);
            return new Scope(section.contentStart(), section.end());
        }
        String requestedTitle = stringValue(patch.get("sectionTitle"));
        if (!requestedTitle.isBlank()) {
            String normalizedTitle = normalizeForMatch(requestedTitle);
            List<SectionSpan> matches = sections.stream()
                    .filter(section -> normalizeForMatch(section.title()).equals(normalizedTitle))
                    .toList();
            if (matches.size() == 1) return new Scope(matches.get(0).contentStart(), matches.get(0).end());
        }
        return new Scope(0, tex.length());
    }

    private List<Integer> occurrences(String text, String needle, int start, int end) {
        List<Integer> result = new ArrayList<>();
        if (needle == null || needle.isEmpty()) return result;
        int offset = Math.max(0, start);
        int limit = Math.min(text.length(), end);
        while (offset <= limit - needle.length()) {
            int found = text.indexOf(needle, offset);
            if (found < 0 || found + needle.length() > limit) break;
            result.add(found);
            offset = found + Math.max(1, needle.length());
        }
        return result;
    }

    private NormalizedText normalizeWithOffsets(String value, int baseOffset) {
        StringBuilder normalized = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < value.length(); index++) {
            char ch = Character.toLowerCase(value.charAt(index));
            if (!Character.isLetterOrDigit(ch)) continue;
            normalized.append(ch);
            offsets.add(baseOffset + index);
        }
        return new NormalizedText(normalized.toString(), offsets);
    }

    private String normalizeForMatch(String value) {
        return normalizeWithOffsets(value == null ? "" : value, 0).text();
    }

    private List<String> words(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private double tokenDice(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : left) counts.merge(token, 1, Integer::sum);
        int overlap = 0;
        for (String token : right) {
            int remaining = counts.getOrDefault(token, 0);
            if (remaining > 0) {
                overlap++;
                counts.put(token, remaining - 1);
            }
        }
        return (2.0 * overlap) / (left.size() + right.size());
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? null : Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException ignored) { return null; }
    }

    private Map<String, Object> parsePatch(String patchJson) {
        if (patchJson == null || patchJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(patchJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private boolean hasSupportedClosure(Suggestion suggestion) {
        Map<String, Object> patch = parsePatch(suggestion == null ? null : suggestion.getPatchJson());
        Map<String, Object> closure = mapValue(patch.get("citationClosure"));
        return "SUPPORTED".equalsIgnoreCase(stringValue(closure.get("status")));
    }

    private Map<String, Object> status(Suggestion suggestion, List<String> keys, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", suggestion.getId());
        result.put("status", status);
        result.put("bibKeys", keys);
        result.put("message", message);
        result.put("anchor", parsePatch(suggestion.getPatchJson()).getOrDefault("anchor", ""));
        return result;
    }

    private String bibEntry(String key, LiteratureCard card) {
        boolean proceedings = notBlank(card.getVenue())
                && (card.getVenue().toLowerCase(Locale.ROOT).contains("conference")
                || card.getVenue().toLowerCase(Locale.ROOT).contains("proceedings"));
        StringBuilder bib = new StringBuilder(proceedings ? "@inproceedings{" : "@article{").append(key).append(",\n")
                .append("  title={").append(escapeBib(card.getTitle())).append("},\n")
                .append("  author={").append(escapeBib(authors(card))).append("},\n");
        if (card.getPublicationYear() != null) bib.append("  year={").append(card.getPublicationYear()).append("},\n");
        if (notBlank(card.getVenue())) bib.append(proceedings ? "  booktitle={" : "  journal={")
                .append(escapeBib(card.getVenue())).append("},\n");
        if (notBlank(card.getDoi())) bib.append("  doi={").append(escapeBib(card.getDoi())).append("},\n");
        if (notBlank(card.getUrl())) bib.append("  url={").append(escapeBib(card.getUrl())).append("},\n");
        return bib.append("}\n\n").toString();
    }

    private String provenanceBlock(String key, CardProvenance provenance) {
        return "% === YANBAN_RECOMMENDED_BEGIN ===\n"
                + "% bibKey=" + key + " suggestionId=" + provenance.suggestionIds().stream().map(String::valueOf).collect(Collectors.joining(","))
                + " cardId=" + provenance.card().getId() + "\n"
                + bibEntry(key, provenance.card())
                + "% === YANBAN_RECOMMENDED_END ===\n\n";
    }

    private Map<String, Object> cardSummary(LiteratureCard card) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cardId", card.getId());
        summary.put("title", card.getTitle());
        summary.put("doi", card.getDoi());
        summary.put("year", card.getPublicationYear());
        return summary;
    }

    private String authors(LiteratureCard card) {
        if (!notBlank(card.getAuthors())) return "Unknown";
        try {
            List<String> values = objectMapper.readValue(card.getAuthors(), new TypeReference<List<String>>() {});
            return values.isEmpty() ? "Unknown" : String.join(" and ", values);
        } catch (Exception ignored) {
            return card.getAuthors();
        }
    }

    private String firstAuthor(LiteratureCard card) {
        String author = authors(card);
        int separator = author.indexOf(" and ");
        return separator > 0 ? author.substring(0, separator) : author;
    }

    private String slug(String value) {
        String ascii = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "reference" : slug;
    }

    private String titleFingerprint(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT).replaceAll("[{}]", "").replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private String normalizeDoi(String doi) {
        if (doi == null) return "";
        return doi.toLowerCase(Locale.ROOT).replace("https://doi.org/", "").replace("http://doi.org/", "").replace("doi:", "").trim();
    }

    private String cardIdentity(LiteratureCard card) {
        String doi = normalizeDoi(card.getDoi());
        if (!doi.isBlank()) return "doi:" + doi;
        String title = titleFingerprint(card.getTitle());
        return title.isBlank() ? "card:" + card.getId() : "title:" + title;
    }

    private String escapeBib(String value) { return value == null ? "" : value.replace("{", "\\{").replace("}", "\\}"); }
    private String stringValue(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private String normalizeFilename(String filename) { return notBlank(filename) && filename.toLowerCase(Locale.ROOT).endsWith(".bib") ? filename : "references.bib"; }

    public record CitationApplyResult(String polishedTex,
                                      String mergedBib,
                                      String novelBib,
                                      List<Map<String, Object>> novelCards,
                                      List<Map<String, Object>> bibliography,
                                      List<Map<String, Object>> appliedPatches,
                                      List<Map<String, Object>> manualPatches,
                                      int existingBibliographyCount,
                                      Set<String> newBibKeys,
                                      String bibFilename) {
        public int acceptedSuggestionCount() { return bibliography.stream().map(item -> item.get("suggestionId")).collect(Collectors.toSet()).size(); }
    }

    private record CardProvenance(LiteratureCard card, Set<Long> suggestionIds) {}
    private record CitationMatch(int keyStart, int keyEnd, int commandEnd, String keys) {}
    private record SlotReplacement(String text, String matchMode) {}
    private record ClosurePatchResult(boolean applied, String text, String status, String message) {
        private static ClosurePatchResult applied(String text) {
            return new ClosurePatchResult(true, text, "APPLIED", "");
        }
        private static ClosurePatchResult failed(String status, String message) {
            return new ClosurePatchResult(false, "", status, message);
        }
    }
    private record Scope(int start, int end) {}
    private record SectionSpan(int commandStart, int contentStart, int end, String title) {}
    private record NormalizedText(String text, List<Integer> offsets) {}
    private record ScoredSpan(int start, int end, double score) {}
    private record AnchorMatch(boolean matched, int start, int end, String mode, String status, String message) {
        private static AnchorMatch matched(int start, int end, String mode) {
            return new AnchorMatch(true, start, end, mode, "APPLIED", "");
        }
        private static AnchorMatch failed(String status, String message) {
            return new AnchorMatch(false, -1, -1, "", status, message);
        }
    }
}
