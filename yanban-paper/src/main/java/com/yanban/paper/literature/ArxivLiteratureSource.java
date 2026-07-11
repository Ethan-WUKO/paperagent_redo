package com.yanban.paper.literature;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class ArxivLiteratureSource implements LiteratureSource {

    private final RestClient restClient;

    public ArxivLiteratureSource() {
        this(RestClient.builder().baseUrl("https://export.arxiv.org").build());
    }

    public ArxivLiteratureSource(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public String name() {
        return "arxiv";
    }

    @Override
    public List<LiteratureCandidate> search(String query, int limit) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String xml = restClient.get()
                .uri("/api/query?search_query=all:" + encoded + "&start=0&max_results=" + Math.max(1, Math.min(limit, 50)))
                .retrieve()
                .body(String.class);
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        return parse(xml, query);
    }

    public List<LiteratureCandidate> parse(String xml, String query) {
        try {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList entries = document.getElementsByTagName("entry");
            List<LiteratureCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String idUrl = text(entry, "id");
                String arxivId = idUrl == null ? null : idUrl.substring(idUrl.lastIndexOf('/') + 1);
                candidates.add(new LiteratureCandidate(
                        name(),
                        null,
                        arxivId,
                        null,
                        null,
                        normalize(text(entry, "title")),
                        authors(entry.getElementsByTagName("author")),
                        year(text(entry, "published")),
                        "arXiv",
                        normalize(text(entry, "summary")),
                        idUrl,
                        pdfLink(entry),
                        null,
                        List.of(),
                        categories(entry.getElementsByTagName("category")),
                        query
                ));
            }
            return candidates;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse arXiv response", ex);
        }
    }

    private String text(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent();
    }

    private List<String> authors(NodeList authorNodes) {
        List<String> authors = new ArrayList<>();
        for (int i = 0; i < authorNodes.getLength(); i++) {
            Element author = (Element) authorNodes.item(i);
            String name = text(author, "name");
            if (name != null && !name.isBlank()) authors.add(normalize(name));
        }
        return authors;
    }

    private List<String> categories(NodeList nodes) {
        List<String> categories = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element category = (Element) nodes.item(i);
            String term = category.getAttribute("term");
            if (term != null && !term.isBlank()) categories.add(term);
        }
        return categories;
    }

    private String pdfLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            if ("application/pdf".equals(link.getAttribute("type"))) {
                return link.getAttribute("href");
            }
        }
        return null;
    }

    private Integer year(String published) {
        if (published == null || published.length() < 4) return null;
        try {
            return Integer.parseInt(published.substring(0, 4));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }
}
