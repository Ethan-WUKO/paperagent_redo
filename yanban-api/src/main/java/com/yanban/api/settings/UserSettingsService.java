package com.yanban.api.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.user.UserAccountPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserSettingsService {

    public static final String DEFAULT_PROVIDER = "deepseek";
    public static final String PROVIDER_GLM = "glm";
    public static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_GLM_MODEL = "glm-5.2";
    public static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
    public static final int DEFAULT_MAX_STEPS = 20;
    public static final boolean DEFAULT_RAG_ENABLED = true;
    public static final List<String> DEFAULT_FILESYSTEM_ROOTS = List.of("workspace");
    public static final List<String> DEFAULT_DEEPSEEK_MODELS = List.of(
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "deepseek-chat",
            "deepseek-reasoner"
    );
    public static final List<String> DEFAULT_GLM_MODELS = List.of(
            "glm-5.2",
            "glm-5.1",
            "glm-5",
            "glm-5-turbo",
            "glm-4.7",
            "glm-4.7-flashx",
            "glm-4.6",
            "glm-4.5-air",
            "glm-4.5-airx",
            "glm-4-long",
            "glm-4.7-flash",
            "glm-4.5-flash",
            "glm-4-flash-250414",
            "glm-4-flash"
    );

    private final SysUserSettingsRepository repository;
    private final UserModelRepository userModelRepository;
    private final SettingsCryptoService cryptoService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final ObjectMapper objectMapper;
    private final UserAccountPolicy accountPolicy;

    public UserSettingsService(SysUserSettingsRepository repository,
                               UserModelRepository userModelRepository,
                               SettingsCryptoService cryptoService,
                               ModelDiscoveryService modelDiscoveryService,
                               ObjectMapper objectMapper,
                               UserAccountPolicy accountPolicy) {
        this.repository = repository;
        this.userModelRepository = userModelRepository;
        this.cryptoService = cryptoService;
        this.modelDiscoveryService = modelDiscoveryService;
        this.objectMapper = objectMapper;
        this.accountPolicy = accountPolicy;
    }

    @Transactional
    public UserSettingsResponse get(Long userId) {
        SysUserSettings settings = getOrCreate(userId);
        return toResponse(settings);
    }

    @Transactional
    public UserSettingsResponse update(Long userId, UserSettingsRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        SysUserSettings settings = getOrCreate(userId);
        String provider = normalizeProvider(request.defaultProvider(), settings.getDefaultProvider());
        String deepseekModel = StringUtils.hasText(request.deepseekModel()) ? request.deepseekModel().trim() : settings.getDeepseekModel();
        String glmModel = StringUtils.hasText(request.glmModel()) ? request.glmModel().trim() : settings.getGlmModel();
        BigDecimal temperature = request.deepseekTemperature() != null ? request.deepseekTemperature() : settings.getDeepseekTemperature();
        Integer maxSteps = request.maxSteps() != null ? request.maxSteps() : settings.getMaxSteps();
        Boolean ragDefaultEnabled = request.ragDefaultEnabled() != null ? request.ragDefaultEnabled() : settings.getRagDefaultEnabled();
        String encryptedDeepseekApiKey = resolveEncryptedApiKey(settings.getDeepseekApiKeyEncrypted(), request.deepseekApiKey());
        String encryptedGlmApiKey = resolveEncryptedApiKey(settings.getGlmApiKeyEncrypted(), request.glmApiKey());
        String encryptedGithubPat = resolveEncryptedApiKey(settings.getGithubPatEncrypted(), request.githubPat());
        String filesystemRootsText = request.filesystemRoots() == null ? settings.getFilesystemRootsText() : writeJson(request.filesystemRoots());
        String disabledSkillsJson = request.disabledSkills() == null ? settings.getDisabledSkillsJson() : writeJson(request.disabledSkills());
        String deepseekModelsText = request.deepseekModels() == null ? settings.getDeepseekModelsText() : writeJson(request.deepseekModels());
        String glmModelsText = request.glmModels() == null ? settings.getGlmModelsText() : writeJson(request.glmModels());
        settings.update(provider, encryptedDeepseekApiKey, encryptedGlmApiKey, deepseekModel, glmModel,
                encryptedGithubPat, filesystemRootsText, disabledSkillsJson, temperature, maxSteps, ragDefaultEnabled,
                deepseekModelsText, glmModelsText);
        return toResponse(repository.saveAndFlush(settings));
    }

    @Transactional
    public UserSettingsResponse refreshProviderModels(Long userId, String provider) {
        accountPolicy.assertSettingsMutable(userId);
        SysUserSettings settings = getOrCreate(userId);
        String resolvedProvider = normalizeProvider(provider, settings.getDefaultProvider());
        if (DEFAULT_PROVIDER.equals(resolvedProvider)) {
            List<String> models = modelDiscoveryService.discoverDeepSeekModels(decryptDeepseekApiKey(settings));
            String selectedModel = models.contains(settings.getDeepseekModel())
                    ? settings.getDeepseekModel()
                    : models.get(0);
            settings.update(settings.getDefaultProvider(),
                    settings.getDeepseekApiKeyEncrypted(),
                    settings.getGlmApiKeyEncrypted(),
                    selectedModel,
                    settings.getGlmModel(),
                    settings.getGithubPatEncrypted(),
                    settings.getFilesystemRootsText(),
                    settings.getDisabledSkillsJson(),
                    settings.getDeepseekTemperature(),
                    settings.getMaxSteps(),
                    settings.getRagDefaultEnabled(),
                    writeJson(models),
                    settings.getGlmModelsText());
            return toResponse(repository.saveAndFlush(settings));
        }
        if (PROVIDER_GLM.equals(resolvedProvider)) {
            String selectedModel = DEFAULT_GLM_MODELS.contains(settings.getGlmModel())
                    ? settings.getGlmModel()
                    : DEFAULT_GLM_MODELS.get(0);
            settings.update(settings.getDefaultProvider(),
                    settings.getDeepseekApiKeyEncrypted(),
                    settings.getGlmApiKeyEncrypted(),
                    settings.getDeepseekModel(),
                    selectedModel,
                    settings.getGithubPatEncrypted(),
                    settings.getFilesystemRootsText(),
                    settings.getDisabledSkillsJson(),
                    settings.getDeepseekTemperature(),
                    settings.getMaxSteps(),
                    settings.getRagDefaultEnabled(),
                    settings.getDeepseekModelsText(),
                    writeJson(DEFAULT_GLM_MODELS));
            return toResponse(repository.saveAndFlush(settings));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported provider: " + provider);
    }

    @Transactional
    public SysUserSettings getOrCreate(Long userId) {
        return repository.findById(userId)
                .orElseGet(() -> {
                    try {
                        SysUserSettings created = repository.saveAndFlush(defaultSettings(userId));
                        seedBuiltinCustomModels(userId);
                        return created;
                    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                        // Concurrent request already inserted the row; fall back to re-query.
                        return repository.findById(userId)
                                .orElseThrow(() -> ex);
                    }
                });
    }

    // ===== Custom model CRUD =====

    @Transactional
    public List<UserModelResponse> listCustomModels(Long userId) {
        ensureUserInitialized(userId);
        return userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(UserModelResponse::from)
                .toList();
    }

    @Transactional
    public UserModelResponse createCustomModel(Long userId, UserModelRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        ensureUserInitialized(userId);
        List<UserModel> existing = userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        int nextSortOrder = existing.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getBuiltin()))
                .mapToInt(UserModel::getSortOrder)
                .max()
                .orElse(100) + 1;
        String providerKey = "custom-" + System.currentTimeMillis();
        UserModel model = new UserModel(
                userId,
                providerKey,
                request.label().trim(),
                request.modelName().trim(),
                request.apiUrl().trim(),
                StringUtils.hasText(request.apiKey()) ? cryptoService.encrypt(request.apiKey().trim()) : null,
                false,
                nextSortOrder
        );
        return UserModelResponse.from(userModelRepository.saveAndFlush(model));
    }

    @Transactional
    public UserModelResponse updateCustomModel(Long userId, Long modelId, UserModelRequest request) {
        accountPolicy.assertSettingsMutable(userId);
        UserModel model = findOwnedCustomModel(userId, modelId);
        String encryptedApiKey = resolveEncryptedApiKey(model.getApiKeyEncrypted(), request.apiKey());
        model.update(request.label().trim(), request.modelName().trim(), request.apiUrl().trim(), encryptedApiKey);
        return UserModelResponse.from(userModelRepository.saveAndFlush(model));
    }

    @Transactional
    public void deleteCustomModel(Long userId, Long modelId) {
        accountPolicy.assertSettingsMutable(userId);
        UserModel model = findOwnedCustomModel(userId, modelId);
        userModelRepository.delete(model);
        userModelRepository.flush();
    }

    private UserModel findOwnedCustomModel(Long userId, Long modelId) {
        UserModel model = userModelRepository.findById(modelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义模型不存在"));
        if (!model.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自定义模型不存在");
        }
        if (Boolean.TRUE.equals(model.getBuiltin())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置模型不可修改或删除");
        }
        return model;
    }

    private void ensureUserInitialized(Long userId) {
        getOrCreate(userId);
    }

    // ===== Model resolution for AgentService =====

    /**
     * Returns the model config (providerKey, modelName, apiUrl, apiKey) for a given provider snapshot.
     * For built-in providers (deepseek/glm), uses settings + global config.
     * For custom providers, looks up the user_models table.
     */
    public ModelEndpoint resolveModelEndpoint(Long userId, String provider, String model) {
        SysUserSettings settings = getOrCreate(userId);
        String resolvedProvider = (provider == null || provider.isBlank())
                ? settings.getDefaultProvider() : provider.trim().toLowerCase();

        if (DEFAULT_PROVIDER.equals(resolvedProvider)) {
            return new ModelEndpoint(resolvedProvider,
                    StringUtils.hasText(model) ? model : settings.getDeepseekModel(),
                    null,
                    decryptDeepseekApiKey(settings));
        }
        if (PROVIDER_GLM.equals(resolvedProvider)) {
            return new ModelEndpoint(resolvedProvider,
                    StringUtils.hasText(model) ? model : settings.getGlmModel(),
                    null,
                    decryptGlmApiKey(settings));
        }
        // Custom provider: providerKey stored as-is (case-sensitive match by providerKey)
        List<UserModel> userModels = userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
        Optional<UserModel> match = userModels.stream()
                .filter(m -> m.getProviderKey().equals(provider))
                .findFirst();
        if (match.isEmpty()) {
            // Fallback: match by model name
            match = userModels.stream()
                    .filter(m -> m.getModelName().equals(model))
                    .findFirst();
        }
        if (match.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未找到模型配置: " + provider);
        }
        UserModel um = match.get();
        return new ModelEndpoint(um.getProviderKey(),
                um.getModelName(),
                um.getApiUrl(),
                decryptCustomModelApiKey(um));
    }

    public String decryptDeepseekApiKey(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getDeepseekApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getDeepseekApiKeyEncrypted());
    }

    public String decryptGlmApiKey(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getGlmApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getGlmApiKeyEncrypted());
    }

    public String decryptGithubPat(SysUserSettings settings) {
        if (!StringUtils.hasText(settings.getGithubPatEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(settings.getGithubPatEncrypted());
    }

    public String decryptCustomModelApiKey(UserModel model) {
        if (!StringUtils.hasText(model.getApiKeyEncrypted())) {
            return null;
        }
        return cryptoService.decrypt(model.getApiKeyEncrypted());
    }

    public List<String> parseFilesystemRoots(SysUserSettings settings) {
        return readStringList(settings.getFilesystemRootsText(), DEFAULT_FILESYSTEM_ROOTS);
    }

    public List<String> parseDisabledSkills(SysUserSettings settings) {
        return readStringList(settings.getDisabledSkillsJson(), List.of());
    }

    public List<String> parseDeepseekModels(SysUserSettings settings) {
        return readStringList(settings.getDeepseekModelsText(), DEFAULT_DEEPSEEK_MODELS);
    }

    public List<String> parseGlmModels(SysUserSettings settings) {
        return readStringList(settings.getGlmModelsText(), DEFAULT_GLM_MODELS);
    }

    private UserSettingsResponse toResponse(SysUserSettings settings) {
        List<UserModelResponse> customModels = userModelRepository
                .findByUserIdOrderBySortOrderAscIdAsc(settings.getUserId()).stream()
                .map(UserModelResponse::from)
                .toList();
        return UserSettingsResponse.from(settings,
                parseFilesystemRoots(settings),
                parseDisabledSkills(settings),
                parseDeepseekModels(settings),
                parseGlmModels(settings),
                customModels);
    }

    private void seedBuiltinCustomModels(Long userId) {
        if (!userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).isEmpty()) {
            return;
        }
        List<UserModel> builtins = List.of(
                new UserModel(userId, "deepseek", "DeepSeek", "deepseek-v4-flash", null, null, true, 1),
                new UserModel(userId, "deepseek", "DeepSeek", "deepseek-v4-pro", null, null, true, 2),
                new UserModel(userId, "deepseek", "DeepSeek", "deepseek-chat", null, null, true, 3),
                new UserModel(userId, "deepseek", "DeepSeek", "deepseek-reasoner", null, null, true, 4),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5.2", null, null, true, 5),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5.1", null, null, true, 6),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-5", null, null, true, 7),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.7", null, null, true, 8),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.6", null, null, true, 9),
                new UserModel(userId, "glm", "Zhipu GLM", "glm-4.5-air", null, null, true, 10)
        );
        userModelRepository.saveAllAndFlush(builtins);
    }

    private SysUserSettings defaultSettings(Long userId) {
        return new SysUserSettings(
                userId,
                DEFAULT_PROVIDER,
                null,
                null,
                DEFAULT_DEEPSEEK_MODEL,
                DEFAULT_GLM_MODEL,
                null,
                writeJson(DEFAULT_FILESYSTEM_ROOTS),
                writeJson(List.of()),
                DEFAULT_TEMPERATURE,
                DEFAULT_MAX_STEPS,
                DEFAULT_RAG_ENABLED
        );
    }

    private String normalizeProvider(String provider, String fallback) {
        String resolved = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : fallback;
        if (!DEFAULT_PROVIDER.equals(resolved) && !PROVIDER_GLM.equals(resolved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认模型提供方仅支持 deepseek 或 glm");
        }
        return resolved;
    }

    private String resolveEncryptedApiKey(String existingEncryptedValue, String apiKey) {
        if (apiKey == null) {
            return existingEncryptedValue;
        }
        if (apiKey.isBlank()) {
            return null;
        }
        return cryptoService.encrypt(apiKey.trim());
    }

    private List<String> readStringList(String json, List<String> fallback) {
        if (!StringUtils.hasText(json)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "序列化设置失败", ex);
        }
    }

    /** Resolved model endpoint for the agent harness. */
    public record ModelEndpoint(String providerKey, String modelName, String apiUrl, String apiKey) {
    }
}
