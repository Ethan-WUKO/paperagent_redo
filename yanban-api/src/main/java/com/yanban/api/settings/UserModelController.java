package com.yanban.api.settings;

import com.yanban.api.security.JwtUser;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
public class UserModelController {

    private final UserSettingsService userSettingsService;
    private final ChatModelProvider chatModelProvider;

    public UserModelController(UserSettingsService userSettingsService, ChatModelProvider chatModelProvider) {
        this.userSettingsService = userSettingsService;
        this.chatModelProvider = chatModelProvider;
    }

    @GetMapping
    public List<UserModelResponse> list(@AuthenticationPrincipal JwtUser currentUser) {
        return userSettingsService.listCustomModels(currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserModelResponse create(@AuthenticationPrincipal JwtUser currentUser,
                                    @Valid @RequestBody UserModelRequest request) {
        return userSettingsService.createCustomModel(currentUser.id(), request);
    }

    @PutMapping("/{id}")
    public UserModelResponse update(@AuthenticationPrincipal JwtUser currentUser,
                                     @PathVariable Long id,
                                     @Valid @RequestBody UserModelRequest request) {
        return userSettingsService.updateCustomModel(currentUser.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal JwtUser currentUser, @PathVariable Long id) {
        userSettingsService.deleteCustomModel(currentUser.id(), id);
    }

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@AuthenticationPrincipal JwtUser currentUser, @PathVariable Long id) {
        List<UserModelResponse> models = userSettingsService.listCustomModels(currentUser.id());
        UserModelResponse model = models.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("模型不存在"));

        UserSettingsService.ModelEndpoint resolved = userSettingsService.resolveModelEndpoint(
                currentUser.id(), model.providerKey(), model.modelName());
        if (resolved.apiUrl() == null) {
            return Map.of("success", false, "error", "该模型为内置模型，请通过设置页面配置 API Key");
        }
        try {
            ChatResponse response = chatModelProvider.chat(new ChatRequest(
                    model.providerKey(),
                    resolved.modelName(),
                    List.of(
                            ChatMessage.system("You are a connection test. Reply with OK."),
                            ChatMessage.user("ping")
                    ),
                    0.1,
                    16,
                    null,
                    resolved.apiKey(),
                    null,
                    null,
                    null,
                    null
            ));
            String content = response == null || response.message() == null ? "" : response.message().content();
            return Map.of("success", true, "content", content == null ? "" : content);
        } catch (Exception ex) {
            return Map.of("success", false, "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
}
