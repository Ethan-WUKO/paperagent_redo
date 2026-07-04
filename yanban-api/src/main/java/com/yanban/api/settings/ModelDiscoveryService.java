package com.yanban.api.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.DeepSeekProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelDiscoveryService {

    private final DeepSeekProperties deepSeekProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ModelDiscoveryService(DeepSeekProperties deepSeekProperties,
                                 WebClient.Builder webClientBuilder,
                                 ObjectMapper objectMapper) {
        this.deepSeekProperties = deepSeekProperties;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<String> discoverDeepSeekModels(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DeepSeek API key is required before refreshing models.");
        }
        if (!StringUtils.hasText(deepSeekProperties.getModelsUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DeepSeek modelsUrl is not configured.");
        }

        try {
            String body = webClient.get()
                    .uri(deepSeekProperties.getModelsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(deepSeekProperties.getTimeout());
            List<String> modelIds = parseModelIds(body);
            if (modelIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek models API returned no model ids.");
            }
            return modelIds;
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek models API failed: HTTP " + ex.getStatusCode().value(), ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek models API request failed.", ex);
        }
    }

    private List<String> parseModelIds(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (StringUtils.hasText(id)) {
                    ids.add(id.trim());
                }
            }
            return new ArrayList<>(ids);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to parse DeepSeek models response.", ex);
        }
    }
}
