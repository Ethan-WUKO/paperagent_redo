package com.yanban.paper.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaperStorageProperties.class)
public class PaperStorageConfig {
}
