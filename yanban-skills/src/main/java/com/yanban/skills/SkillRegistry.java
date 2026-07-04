package com.yanban.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SkillRegistry {

    private final SkillLoader skillLoader;
    private volatile Map<String, SkillDefinition> cache = Map.of();

    public SkillRegistry(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
        refresh();
    }

    public synchronized void refresh() {
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        for (SkillDefinition skill : skillLoader.loadAll()) {
            loaded.put(skill.id(), skill);
        }
        cache = loaded;
    }

    public List<SkillDefinition> list() {
        return List.copyOf(cache.values());
    }

    public Optional<SkillDefinition> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }
}
