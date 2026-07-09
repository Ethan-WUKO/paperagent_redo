package com.yanban.api.settings;

import com.yanban.api.security.JwtUser;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import jakarta.validation.Valid;
import java.util.List;
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
    private final ModelTestFailureClassifier failureClassifier;

    public UserModelController(UserSettingsService userSettingsService,
                               ChatModelProvider chatModelProvider,
                               ModelTestFailureClassifier failureClassifier) {
        this.userSettingsService = userSettingsService;
        this.chatModelProvider = chatModelProvider;
        this.failureClassifier = failureClassifier;
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
    public ModelTestResponse test(@AuthenticationPrincipal JwtUser currentUser, @PathVariable Long id) {
        List<UserModelResponse> models = userSettingsService.listCustomModels(currentUser.id());
        UserModelResponse model = models.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model does not exist"));

        UserSettingsService.ModelEndpoint resolved = userSettingsService.resolveModelEndpoint(
                currentUser.id(), model.providerKey(), model.modelName());
        if (resolved.apiUrl() == null) {
            return ModelTestResponse.failure(
                    ModelTestErrorType.ADDRESS_ERROR,
                    "This model does not have a custom chat-completions API URL configured.",
                    resolved.providerKey(),
                    resolved.modelName());
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
                    resolved.apiUrl(),
                    null,
                    null,
                    null
            ));
            String content = response == null || response.message() == null ? "" : response.message().content();
            return ModelTestResponse.success(content, resolved.providerKey(), resolved.modelName());
        } catch (Exception ex) {
            return failureClassifier.classify(ex, resolved);
        }
    }
}
