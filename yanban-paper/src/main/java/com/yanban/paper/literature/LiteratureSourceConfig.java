package com.yanban.paper.literature;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LiteratureSourceConfig {

    @Bean
    public LiteratureSource openAlexLiteratureSource() {
        return new OpenAlexLiteratureSource();
    }

    @Bean
    public LiteratureSource arxivLiteratureSource() {
        return new ArxivLiteratureSource();
    }

}
