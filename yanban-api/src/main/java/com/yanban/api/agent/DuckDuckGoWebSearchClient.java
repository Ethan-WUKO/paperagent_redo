package com.yanban.api.agent;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DuckDuckGoWebSearchClient implements WebSearchClient {

    private static final Pattern RESULT_LINK_PATTERN = Pattern.compile(
            "(?is)<a[^>]+class=[\"'][^\"']*result__a[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "(?is)class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>");
    private static final int RESULT_WINDOW = 1800;

    private final RestClient restClient;
    private final WebSearchProperties properties;

    public DuckDuckGoWebSearchClient(RestClient.Builder restClientBuilder,
                                     WebSearchProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "duckduckgo";
    }

    @Override
    public boolean available() {
        return StringUtils.hasText(properties.getEndpoint());
    }

    @Override
    public String unavailableReason() {
        return available() ? "" : "duckduckgo_endpoint_missing";
    }

    @Override
    public WebSearchResponse search(String query, int limit) {
        try {
            String html = restClient.get()
                    .uri(searchUri(query))
                    .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 YanbanAgent/0.1")
                    .retrieve()
                    .body(String.class);
            return parseResults(query.trim(), limit, html);
        } catch (Exception ex) {
            return degraded(query, limit, abbreviate(errorMessage(ex), 500));
        }
    }

    private String searchUri(String query) {
        return UriComponentsBuilder.fromUriString(properties.getEndpoint())
                .queryParam("q", query)
                .build()
                .encode()
                .toUriString();
    }

    private WebSearchResponse parseResults(String query, int limit, String html) {
        if (!StringUtils.hasText(html)) {
            return degraded(query, limit, "empty_search_response");
        }
        Matcher matcher = RESULT_LINK_PATTERN.matcher(html);
        List<WebSearchItem> items = new ArrayList<>();
        while (matcher.find() && items.size() < limit) {
            String href = normalizeUrl(matcher.group(1));
            String title = cleanHtml(matcher.group(2));
            String snippetWindow = html.substring(matcher.end(), Math.min(html.length(), matcher.end() + RESULT_WINDOW));
            String snippet = firstSnippet(snippetWindow);
            if (!StringUtils.hasText(title) || !StringUtils.hasText(href)) {
                continue;
            }
            items.add(new WebSearchItem(title, href, snippet, null, null, "duckduckgo_html"));
        }
        if (items.isEmpty()) {
            return degraded(query, limit, "no_web_results_parsed");
        }
        return new WebSearchResponse(provider(), query, false, "", limit, items, null, null, null);
    }

    private WebSearchResponse degraded(String query, int limit, String error) {
        return new WebSearchResponse(provider(), query, true, error, limit, List.of(), null, null, null);
    }

    private String firstSnippet(String htmlWindow) {
        Matcher matcher = SNIPPET_PATTERN.matcher(htmlWindow);
        if (!matcher.find()) {
            return "";
        }
        return cleanHtml(matcher.group(1));
    }

    private String normalizeUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return "";
        }
        String value = HtmlUtils.htmlUnescape(href.trim());
        int uddgIndex = value.indexOf("uddg=");
        if (uddgIndex >= 0) {
            String encoded = value.substring(uddgIndex + "uddg=".length());
            int amp = encoded.indexOf('&');
            if (amp >= 0) {
                encoded = encoded.substring(0, amp);
            }
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        return value;
    }

    private String cleanHtml(String value) {
        if (value == null) {
            return "";
        }
        String withoutTags = value.replaceAll("(?is)<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
    }

    private String errorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
