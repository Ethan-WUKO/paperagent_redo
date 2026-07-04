package com.yanban.knowledge.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class KnowledgeQueryVariants {

    private static final Pattern LOOKUP_TOKEN = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)+");
    private static final Pattern LONG_TOKEN = Pattern.compile("[a-z0-9_]{8,}");
    private static final int MAX_VARIANTS = 6;
    private static final Set<String> STOP_WORDS = Set.of(
            "answer", "citation", "contains", "exact", "fact", "find", "from", "knowledge",
            "please", "return", "source", "supports", "uploaded", "using", "verify", "which"
    );

    private KnowledgeQueryVariants() {
    }

    static List<String> expand(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        add(variants, normalized);
        addMatches(variants, LOOKUP_TOKEN.matcher(normalized));
        addMatches(variants, LONG_TOKEN.matcher(normalized));
        return variants.stream().limit(MAX_VARIANTS).toList();
    }

    private static void addMatches(LinkedHashSet<String> variants, Matcher matcher) {
        while (matcher.find()) {
            add(variants, matcher.group());
        }
    }

    private static void add(LinkedHashSet<String> variants, String value) {
        String cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("^[^a-z0-9_]+|[^a-z0-9_]+$", "");
        if (!StringUtils.hasText(cleaned) || STOP_WORDS.contains(cleaned)) {
            return;
        }
        variants.add(cleaned);
    }
}
