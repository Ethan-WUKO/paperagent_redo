package com.yanban.api;

import org.springframework.boot.SpringApplication;
import com.yanban.api.security.JwtProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableConfigurationProperties(JwtProperties.class)
@EntityScan(basePackages = "com.yanban")
@EnableJpaRepositories(basePackages = "com.yanban")
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.yanban")
public class YanbanApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(YanbanApiApplication.class, args);
    }
}
