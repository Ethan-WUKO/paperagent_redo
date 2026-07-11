package com.yanban.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SkillLoader {

    public List<SkillDefinition> loadAll() {
        List<SkillDefinition> skills = new ArrayList<>();
        skills.addAll(scanRoot(resolveRoot("builtin"), true));
        skills.addAll(scanRoot(resolveRoot("user"), false));
        return skills;
    }

    private Path resolveRoot(String kind) {
        Path direct = Path.of("skills", kind);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Path.of("..", "skills", kind).normalize();
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return direct;
    }

    private List<SkillDefinition> scanRoot(Path root, boolean builtin) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted()
                    .map(path -> loadSkill(path, builtin))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("扫描 skills 目录失败: " + root, ex);
        }
    }

    private SkillDefinition loadSkill(Path path, boolean builtin) {
        Path markdown = path.resolve("SKILL.md");
        if (!Files.exists(markdown)) {
            return null;
        }
        try {
            String prompt = Files.readString(markdown);
            SkillMeta meta = loadMeta(path.resolve("skill.yaml"));
            String id = path.getFileName().toString();
            return new SkillDefinition(
                    id,
                    meta.name() == null ? id : meta.name(),
                    meta.description() == null ? (builtin ? "内置 Skill" : "用户 Skill") : meta.description(),
                    builtin,
                    path,
                    prompt,
                    meta.allowedTools()
            );
        } catch (IOException ex) {
            throw new IllegalStateException("读取 Skill 失败: " + path, ex);
        }
    }

    private SkillMeta loadMeta(Path yamlPath) throws IOException {
        if (!Files.exists(yamlPath)) {
            return new SkillMeta(null, null, List.of());
        }
        String name = null;
        String description = null;
        List<String> allowedTools = new ArrayList<>();
        boolean inAllowedTools = false;
        for (String rawLine : Files.readAllLines(yamlPath)) {
            String line = rawLine.stripTrailing();
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring(5).trim();
                inAllowedTools = false;
                continue;
            }
            if (trimmed.startsWith("description:")) {
                description = trimmed.substring(12).trim();
                inAllowedTools = false;
                continue;
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("allowed_tools:")) {
                inAllowedTools = true;
                continue;
            }
            if (inAllowedTools && trimmed.startsWith("-")) {
                allowedTools.add(trimmed.substring(1).trim());
                continue;
            }
            inAllowedTools = false;
        }
        return new SkillMeta(name, description, allowedTools);
    }

    private record SkillMeta(String name, String description, List<String> allowedTools) {}
}
