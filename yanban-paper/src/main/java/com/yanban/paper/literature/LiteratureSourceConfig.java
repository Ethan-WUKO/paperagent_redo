package com.yanban.paper.literature;

import com.yanban.paper.config.PaperLiteratureProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LiteratureSourceConfig {

    @Bean
    public LiteratureSource openAlexLiteratureSource(PaperLiteratureProperties properties) {
        return new OpenAlexLiteratureSource(
                RestClient.builder().baseUrl("https://api.openalex.org").build(),
                properties.getOpenAlexApiKey());
    }

    @Bean
    public LiteratureSource arxivLiteratureSource() {
        return new ArxivLiteratureSource();
    }

}
