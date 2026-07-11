package com.yanban.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class CliConfigStore {

    private final Path configPath;

    public CliConfigStore() {
        this(Path.of(System.getProperty("user.home"), ".yanban-agent", "config.properties"));
    }

    public CliConfigStore(Path configPath) {
        this.configPath = configPath;
    }

    public Properties load() {
        Properties properties = new Properties();
        if (!Files.exists(configPath)) {
            return properties;
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            properties.load(in);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("读取 CLI 配置失败: " + configPath, ex);
        }
    }

    public void save(Properties properties) {
        try {
            Files.createDirectories(configPath.getParent());
            try (OutputStream out = Files.newOutputStream(configPath)) {
                properties.store(out, "Yanban CLI config");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("保存 CLI 配置失败: " + configPath, ex);
        }
    }

    public Path getConfigPath() {
        return configPath;
    }
}
