package com.yanban.paper.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class PaperPromptService {

    private static final String PROMPT_LOCATION_PATTERN = "classpath*:prompts/*.md";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");

    private final Map<String, PaperPromptTemplate> templates;

    public PaperPromptService() {
        this.templates = loadTemplates();
    }

    public PaperPromptTemplate getTemplate(String name) {
        PaperPromptTemplate template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Unknown paper prompt: " + name);
        }
        return template;
    }

    public String render(String name, Map<String, ?> variables) {
        PaperPromptTemplate template = getTemplate(name);
        Map<String, ?> values = variables == null ? Map.of() : variables;
        Set<String> missing = new LinkedHashSet<>();
        for (String required : template.variables()) {
            Object value = values.get(required);
            if (value == null) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing prompt variables for " + name + ": " + missing);
        }

        String rendered = template.content();
        for (String variable : template.variables()) {
            rendered = rendered.replace("{{" + variable + "}}", stringify(values.get(variable)));
        }
        return rendered;
    }

    public Set<String> names() {
        return Set.copyOf(templates.keySet());
    }

    private Map<String, PaperPromptTemplate> loadTemplates() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(PROMPT_LOCATION_PATTERN);
            Map<String, PaperPromptTemplate> loaded = new HashMap<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) continue;
                String name = filename.substring(0, filename.length() - 3);
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                loaded.put(name, new PaperPromptTemplate(name, content, extractVariables(content)));
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("No paper prompt templates found under prompts/*.md");
            }
            return Map.copyOf(loaded);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load paper prompt templates", ex);
        }
    }

    private Set<String> extractVariables(String content) {
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        Set<String> variables = new LinkedHashSet<>();
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return Set.copyOf(variables);
    }

    private String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                if (!builder.isEmpty()) builder.append(System.lineSeparator());
                builder.append(item);
            }
            return builder.toString();
        }
        return String.valueOf(value);
    }
}
