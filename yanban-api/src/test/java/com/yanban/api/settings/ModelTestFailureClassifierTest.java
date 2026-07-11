package com.yanban.api.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.model.ModelProviderException;
import java.net.ConnectException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

class ModelTestFailureClassifierTest {

    private final ModelTestFailureClassifier classifier = new ModelTestFailureClassifier();

    @Test
    void classifiesAuthenticationFailures() {
        assertThat(classifier.classifyType(new ModelProviderException("HTTP 401 invalid api key"), "HTTP 401 invalid api key"))
                .isEqualTo(ModelTestErrorType.API_KEY_ERROR);
    }

    @Test
    void classifiesModelNameFailures() {
        assertThat(classifier.classifyType(new ModelProviderException("model_not_found: bad-model"), "model_not_found: bad-model"))
                .isEqualTo(ModelTestErrorType.MODEL_NAME_ERROR);
    }

    @Test
    void classifiesInterfaceFailures() {
        assertThat(classifier.classifyType(new ModelProviderException("OpenAI-compatible API returned no choices"), "returned no choices"))
                .isEqualTo(ModelTestErrorType.INTERFACE_INCOMPATIBLE);
    }

    @Test
    void classifiesAddressFailures() {
        WebClientRequestException ex = new WebClientRequestException(
                new ConnectException("Connection refused"),
                HttpMethod.POST,
                URI.create("https://bad-host.example/v1/chat/completions"),
                HttpHeaders.EMPTY);

        assertThat(classifier.classifyType(ex, "Connection refused"))
                .isEqualTo(ModelTestErrorType.ADDRESS_ERROR);
    }

    @Test
    void redactsEndpointSecretsFromReturnedMessage() {
        UserSettingsService.ModelEndpoint endpoint = new UserSettingsService.ModelEndpoint(
                "custom-1",
                "demo-model",
                "https://secret.example/v1/chat/completions",
                "sk-secret",
                "custom",
                "Demo");

        ModelTestResponse response = classifier.classify(new ModelProviderException(
                "Bearer sk-secret failed at https://secret.example/v1/chat/completions"), endpoint);

        assertThat(response.errorMessage()).doesNotContain("sk-secret");
        assertThat(response.errorMessage()).doesNotContain("https://secret.example");
        assertThat(response.errorType()).isEqualTo(ModelTestErrorType.API_KEY_ERROR);
    }
}
