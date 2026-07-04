package com.yanban.api.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.settings.SysUserSettings;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.skills.SkillDefinition;
import com.yanban.skills.SkillRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SkillsService {

    private final UserSettingsService userSettingsService;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper;

    public SkillsService(UserSettingsService userSettingsService,
                         SkillRegistry skillRegistry,
                         ObjectMapper objectMapper) {
        this.userSettingsService = userSettingsService;
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<SkillListItemResponse> listSkills(Long userId) {
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        Set<String> disabledIds = new HashSet<>(userSettingsService.parseDisabledSkills(settings));
        return skillRegistry.list().stream()
                .map(skill -> new SkillListItemResponse(
                        skill.id(),
                        skill.name(),
                        skill.description(),
                        skill.builtin(),
                        !disabledIds.contains(skill.id()),
                        skill.path().toString().replace('\\', '/')
                ))
                .toList();
    }

    @Transactional
    public void setEnabled(Long userId, String skillId, boolean enabled) {
        SkillDefinition skill = skillRegistry.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 不存在"));
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        Set<String> disabledIds = new HashSet<>(userSettingsService.parseDisabledSkills(settings));
        if (enabled) {
            disabledIds.remove(skill.id());
        } else {
            disabledIds.add(skill.id());
        }
        settings.update(
                settings.getDefaultProvider(),
                settings.getDeepseekApiKeyEncrypted(),
                settings.getGlmApiKeyEncrypted(),
                settings.getDeepseekModel(),
                settings.getGlmModel(),
                settings.getGithubPatEncrypted(),
                settings.getFilesystemRootsText(),
                writeJson(List.copyOf(disabledIds)),
                settings.getDeepseekTemperature(),
                settings.getMaxSteps(),
                settings.getRagDefaultEnabled(),
                settings.getDeepseekModelsText(),
                settings.getGlmModelsText()
        );
    }

    @Transactional
    public ResolvedSkill resolveEnabledSkill(Long userId, String skillId) {
        SkillDefinition skill = skillRegistry.findById(skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill 不存在: " + skillId));
        SysUserSettings settings = userSettingsService.getOrCreate(userId);
        if (userSettingsService.parseDisabledSkills(settings).contains(skillId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill 已被禁用: " + skillId);
        }
        return new ResolvedSkill(skill.id(), skill.prompt(), Set.copyOf(skill.allowedTools()));
    }

    public void refresh() {
        skillRegistry.refresh();
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化禁用 Skill 列表失败", ex);
        }
    }
}
