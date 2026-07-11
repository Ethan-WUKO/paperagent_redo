package com.yanban.api.settings;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Component
public class ModelTestFailureClassifier {

    public ModelTestResponse classify(Throwable ex, UserSettingsService.ModelEndpoint endpoint) {
        String rawMessage = rootMessage(ex);
        String message = sanitize(rawMessage, endpoint);
        return ModelTestResponse.failure(classifyType(ex, rawMessage), message, endpoint.providerKey(), endpoint.modelName());
    }

    ModelTestErrorType classifyType(Throwable ex, String sanitizedMessage) {
        String message = defaultString(sanitizedMessage).toLowerCase(Locale.ROOT);
        if (containsAny(message, "401", "403", "unauthorized", "forbidden", "invalid api key", "incorrect api key",
                "api key", "apikey", "authentication", "permission denied")) {
            return ModelTestErrorType.API_KEY_ERROR;
        }
        if (containsAny(message, "bearer ", "sk-")) {
            return ModelTestErrorType.API_KEY_ERROR;
        }
        if (containsAny(message, "model_not_found", "model not found", "invalid model", "model does not exist",
                "unknown model", "model name", "no such model")) {
            return ModelTestErrorType.MODEL_NAME_ERROR;
        }
        if (containsAny(message, "returned no choices", "returned empty message", "failed to parse",
                "decode", "json", "unsupported", "schema", "not openai-compatible", "not compatible")) {
            return ModelTestErrorType.INTERFACE_INCOMPATIBLE;
        }
        if (containsAny(message, "http 400", "http 404")) {
            return ModelTestErrorType.MODEL_NAME_ERROR;
        }
        if (isAddressFailure(ex) || containsAny(message, "connection refused", "unknownhost", "unknown host",
                "timeout", "timed out", "no route to host", "invalid url", "host is not specified",
                "connection reset", "failed to resolve", "request failed")) {
            return ModelTestErrorType.ADDRESS_ERROR;
        }
        return ModelTestErrorType.UNKNOWN_ERROR;
    }

    private boolean isAddressFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof WebClientRequestException
                    || current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsAny(String source, String... needles) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        for (String needle : needles) {
            if (source.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        String message = null;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return StringUtils.hasText(message) ? message : ex == null ? "Model test failed" : ex.getClass().getSimpleName();
    }

    private String sanitize(String value, UserSettingsService.ModelEndpoint endpoint) {
        String sanitized = defaultString(value);
        if (endpoint != null) {
            sanitized = replaceSecret(sanitized, endpoint.apiKey());
            sanitized = replaceSecret(sanitized, endpoint.apiUrl());
        }
        sanitized = sanitized.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-:/+=]+", "Bearer [redacted]");
        sanitized = sanitized.replaceAll("sk-[A-Za-z0-9._\\-]+", "sk-[redacted]");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private String replaceSecret(String value, String secret) {
        if (!StringUtils.hasText(value) || !StringUtils.hasText(secret)) {
            return value;
        }
        return value.replace(secret, "[redacted]");
    }

    private String defaultString(String value) {
        return StringUtils.hasText(value) ? value.trim() : "Model test failed";
    }
}
