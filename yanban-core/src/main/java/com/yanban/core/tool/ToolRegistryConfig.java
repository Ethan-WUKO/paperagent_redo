package com.yanban.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRegistryConfig {

    @Bean
    public EchoToolExecutor echoToolExecutor(ObjectMapper objectMapper) {
        return new EchoToolExecutor(objectMapper);
    }

    @Bean
    public ToolRegistry toolRegistry(List<ToolExecutor> executors,
                                     ObjectProvider<List<ToolRegistryCustomizer>> customizersProvider) {
        ToolRegistry registry = new ToolRegistry();
        executors.forEach(registry::register);
        List<ToolRegistryCustomizer> customizers = customizersProvider.getIfAvailable();
        if (customizers != null) {
            customizers.forEach(customizer -> customizer.customize(registry));
        }
        return registry;
    }
}
