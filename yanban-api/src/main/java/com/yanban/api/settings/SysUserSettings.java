package com.yanban.api.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "sys_user_settings")
public class SysUserSettings {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "default_provider", nullable = false, length = 64)
    private String defaultProvider;

    @Column(name = "deepseek_api_key_encrypted")
    private String deepseekApiKeyEncrypted;

    @Column(name = "glm_api_key_encrypted")
    private String glmApiKeyEncrypted;

    @Column(name = "deepseek_model", nullable = false, length = 128)
    private String deepseekModel;

    @Column(name = "glm_model", nullable = false, length = 128)
    private String glmModel;

    @Column(name = "deepseek_models")
    private String deepseekModelsText;

    @Column(name = "glm_models")
    private String glmModelsText;

    @Column(name = "github_pat_encrypted")
    private String githubPatEncrypted;

    @Column(name = "filesystem_roots_text")
    private String filesystemRootsText;

    @Column(name = "disabled_skills_json")
    private String disabledSkillsJson;

    @Column(name = "deepseek_temperature", nullable = false, precision = 4, scale = 2)
    private BigDecimal deepseekTemperature;

    @Column(name = "max_steps", nullable = false)
    private Integer maxSteps;

    @Column(name = "rag_default_enabled", nullable = false)
    private Boolean ragDefaultEnabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SysUserSettings() {
    }

    public SysUserSettings(Long userId, String defaultProvider, String deepseekApiKeyEncrypted, String glmApiKeyEncrypted,
                           String deepseekModel, String glmModel, String githubPatEncrypted, String filesystemRootsText,
                           String disabledSkillsJson, BigDecimal deepseekTemperature, Integer maxSteps, Boolean ragDefaultEnabled) {
        this.userId = userId;
        this.defaultProvider = defaultProvider;
        this.deepseekApiKeyEncrypted = deepseekApiKeyEncrypted;
        this.glmApiKeyEncrypted = glmApiKeyEncrypted;
        this.deepseekModel = deepseekModel;
        this.glmModel = glmModel;
        this.githubPatEncrypted = githubPatEncrypted;
        this.filesystemRootsText = filesystemRootsText;
        this.disabledSkillsJson = disabledSkillsJson;
        this.deepseekTemperature = deepseekTemperature;
        this.maxSteps = maxSteps;
        this.ragDefaultEnabled = ragDefaultEnabled;
    }

    public Long getUserId() { return userId; }
    public String getDefaultProvider() { return defaultProvider; }
    public String getDeepseekApiKeyEncrypted() { return deepseekApiKeyEncrypted; }
    public String getGlmApiKeyEncrypted() { return glmApiKeyEncrypted; }
    public String getDeepseekModel() { return deepseekModel; }
    public String getGlmModel() { return glmModel; }
    public String getDeepseekModelsText() { return deepseekModelsText; }
    public String getGlmModelsText() { return glmModelsText; }
    public String getGithubPatEncrypted() { return githubPatEncrypted; }
    public String getFilesystemRootsText() { return filesystemRootsText; }
    public String getDisabledSkillsJson() { return disabledSkillsJson; }
    public BigDecimal getDeepseekTemperature() { return deepseekTemperature; }
    public Integer getMaxSteps() { return maxSteps; }
    public Boolean getRagDefaultEnabled() { return ragDefaultEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String defaultProvider, String deepseekApiKeyEncrypted, String glmApiKeyEncrypted,
                       String deepseekModel, String glmModel, String githubPatEncrypted, String filesystemRootsText,
                       String disabledSkillsJson, BigDecimal deepseekTemperature, Integer maxSteps, Boolean ragDefaultEnabled,
                       String deepseekModelsText, String glmModelsText) {
        this.defaultProvider = defaultProvider;
        this.deepseekApiKeyEncrypted = deepseekApiKeyEncrypted;
        this.glmApiKeyEncrypted = glmApiKeyEncrypted;
        this.deepseekModel = deepseekModel;
        this.glmModel = glmModel;
        this.githubPatEncrypted = githubPatEncrypted;
        this.filesystemRootsText = filesystemRootsText;
        this.disabledSkillsJson = disabledSkillsJson;
        this.deepseekTemperature = deepseekTemperature;
        this.maxSteps = maxSteps;
        this.ragDefaultEnabled = ragDefaultEnabled;
        this.deepseekModelsText = deepseekModelsText;
        this.glmModelsText = glmModelsText;
    }
}
