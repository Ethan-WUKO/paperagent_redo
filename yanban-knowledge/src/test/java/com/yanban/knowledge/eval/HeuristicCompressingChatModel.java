package com.yanban.knowledge.eval;

import dev.langchain4j.model.chat.ChatModel;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

class HeuristicCompressingChatModel implements ChatModel {

    private static final Pattern ENGLISH_TOKEN = Pattern.compile("[a-z0-9][a-z0-9@._-]*");
    private static final Pattern CJK_SEQUENCE = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]{2,}");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "about", "be", "besides", "current", "does", "do",
            "final", "for", "how", "in", "is", "it", "its", "manuscript", "of", "on",
            "paper", "report", "say", "should", "still", "the", "to", "use", "version",
            "what", "which", "wording"
    );

    @Override
    public String chat(String prompt) {
        String query = between(prompt, "User query:", "\n\nIt is very important");
        String chatMemory = between(prompt, "Conversation:", "\n\nUser query:");

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, query);
        if (looksReferential(query)) {
            addTokens(tokens, chatMemory);
        }

        String compressed = String.join(" ", tokens.stream().limit(12).toList()).trim();
        if (StringUtils.hasText(compressed)) {
            return compressed;
        }
        return StringUtils.hasText(query) ? query.trim() : "";
    }

    private void addTokens(LinkedHashSet<String> tokens, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        Matcher englishMatcher = ENGLISH_TOKEN.matcher(normalized);
        while (englishMatcher.find()) {
            String token = englishMatcher.group();
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        Matcher cjkMatcher = CJK_SEQUENCE.matcher(value);
        while (cjkMatcher.find()) {
            tokens.add(cjkMatcher.group());
        }
    }

    private boolean looksReferential(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        return normalized.contains(" it ")
                || normalized.startsWith("it ")
                || normalized.contains(" its ")
                || normalized.startsWith("its ")
                || normalized.contains(" this ")
                || normalized.startsWith("this ")
                || normalized.contains(" that ")
                || normalized.startsWith("that ")
                || normalized.contains(" still ");
    }

    private String between(String text, String startMarker, String endMarker) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int start = text.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        String extracted = end < 0 ? text.substring(start) : text.substring(start, end);
        return extracted.trim();
    }
}
