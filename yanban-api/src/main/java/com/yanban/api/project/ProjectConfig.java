package com.yanban.api.project;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProjectStorageProperties.class)
public class ProjectConfig {
}
