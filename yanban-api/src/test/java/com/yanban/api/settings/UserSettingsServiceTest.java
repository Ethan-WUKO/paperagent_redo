package com.yanban.api.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.OpenRouterProperties;
import com.yanban.core.user.UserAccountPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

    @Mock
    SysUserSettingsRepository repository;

    @Mock
    UserModelRepository userModelRepository;

    @Mock
    SettingsCryptoService cryptoService;

    @Mock
    ModelDiscoveryService modelDiscoveryService;

    @Mock
    UserAccountPolicy accountPolicy;

    @Mock
    UserSettingsInitializer initializer;

    @Mock
    ObjectMapper objectMapper;

    @Mock
    OpenRouterProperties openRouterProperties;

    @InjectMocks
    UserSettingsService service;

    @Test
    void getFallsBackToExistingSettingsWhenConcurrentInsertWins() {
        Long userId = 2L;
        SysUserSettings existing = new SysUserSettings(
                userId,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );

        when(repository.findById(userId)).thenReturn(Optional.empty(), Optional.of(existing));
        when(initializer.createDefaultSettings(eq(userId), any(SysUserSettings.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)).thenReturn(List.of());
        when(openRouterProperties.getHy3FreeModel()).thenReturn("tencent/hy3:free");
        when(openRouterProperties.getHy3Model()).thenReturn("tencent/hy3");
        when(openRouterProperties.getApiUrl()).thenReturn("https://openrouter.ai/api/v1/chat/completions");

        UserSettingsResponse response = service.get(userId);

        assertThat(response.defaultProvider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        verify(initializer).createDefaultSettings(eq(userId), any(SysUserSettings.class));
    }

    @Test
    void resolveOpenRouterBuiltinUsesGlobalApiKeyWhenModelHasNoStoredKey() {
        Long userId = 3L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel hy3 = new UserModel(
                userId,
                UserSettingsService.PROVIDER_OPENROUTER_HY3_FREE,
                "OpenRouter",
                "tencent/hy3:free",
                null,
                null,
                true,
                20
        );
        when(repository.findById(userId)).thenReturn(Optional.of(settings));
        when(userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)).thenReturn(List.of(hy3));
        when(openRouterProperties.getApiUrl()).thenReturn("https://openrouter.ai/api/v1/chat/completions");
        when(openRouterProperties.getApiKey()).thenReturn("or-key");

        UserSettingsService.ModelEndpoint endpoint = service.resolveModelEndpoint(
                userId,
                UserSettingsService.PROVIDER_OPENROUTER_HY3_FREE,
                "tencent/hy3:free");

        assertThat(endpoint.providerKey()).isEqualTo(UserSettingsService.PROVIDER_OPENROUTER_HY3_FREE);
        assertThat(endpoint.modelName()).isEqualTo("tencent/hy3:free");
        assertThat(endpoint.apiUrl()).isEqualTo("https://openrouter.ai/api/v1/chat/completions");
        assertThat(endpoint.apiKey()).isEqualTo("or-key");
        assertThat(endpoint.sourceType()).isEqualTo("builtin-custom");
        assertThat(endpoint.sourceLabel()).isEqualTo("OpenRouter");
    }

    @Test
    void updateCanSelectOwnedCustomModelAsDefaultProvider() throws Exception {
        Long userId = 4L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                UserSettingsService.DEFAULT_PROVIDER,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-abc", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(repository.findById(userId)).thenReturn(Optional.of(settings));
        when(userModelRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)).thenReturn(List.of(custom));
        when(repository.saveAndFlush(settings)).thenReturn(settings);
        UserSettingsRequest request = new UserSettingsRequest(
                "custom-abc",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        UserSettingsResponse response = service.update(userId, request);

        assertThat(response.defaultProvider()).isEqualTo("custom-abc");
    }

    @Test
    void deleteCustomModelResetsDefaultProviderWhenItWasSelected() {
        Long userId = 5L;
        Long modelId = 55L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                "custom-delete",
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-delete", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(userModelRepository.findById(modelId)).thenReturn(Optional.of(custom));
        when(repository.findById(userId)).thenReturn(Optional.of(settings));
        when(repository.saveAndFlush(settings)).thenReturn(settings);

        service.deleteCustomModel(userId, modelId);

        assertThat(settings.getDefaultProvider()).isEqualTo(UserSettingsService.DEFAULT_PROVIDER);
        verify(userModelRepository).delete(custom);
    }

    @Test
    void deleteCustomModelLeavesDifferentDefaultProviderUntouched() {
        Long userId = 6L;
        Long modelId = 66L;
        SysUserSettings settings = new SysUserSettings(
                userId,
                UserSettingsService.PROVIDER_GLM,
                null,
                null,
                UserSettingsService.DEFAULT_DEEPSEEK_MODEL,
                UserSettingsService.DEFAULT_GLM_MODEL,
                null,
                "[]",
                "[]",
                UserSettingsService.DEFAULT_TEMPERATURE,
                UserSettingsService.DEFAULT_MAX_STEPS,
                UserSettingsService.DEFAULT_RAG_ENABLED
        );
        UserModel custom = new UserModel(userId, "custom-other", "Mine", "my-model",
                "https://example.test/v1/chat/completions", "encrypted", false, 101);
        when(userModelRepository.findById(modelId)).thenReturn(Optional.of(custom));
        when(repository.findById(userId)).thenReturn(Optional.of(settings));

        service.deleteCustomModel(userId, modelId);

        assertThat(settings.getDefaultProvider()).isEqualTo(UserSettingsService.PROVIDER_GLM);
        verify(repository, never()).saveAndFlush(settings);
    }
}
