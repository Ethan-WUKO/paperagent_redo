package com.yanban.api.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.mcp")
public class McpProperties {

    private ServerProperties github = new ServerProperties();
    private ServerProperties filesystem = new ServerProperties();

    public ServerProperties getGithub() { return github; }
    public void setGithub(ServerProperties github) { this.github = github; }
    public ServerProperties getFilesystem() { return filesystem; }
    public void setFilesystem(ServerProperties filesystem) { this.filesystem = filesystem; }

    public static class ServerProperties {
        private boolean enabled;
        private List<String> command = new ArrayList<>();
        private List<String> allowedCommands = new ArrayList<>();
        private Map<String, String> environment = new HashMap<>();
        private Duration startupTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(20);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getCommand() { return command; }
        public void setCommand(List<String> command) { this.command = command; }
        public List<String> getAllowedCommands() { return allowedCommands; }
        public void setAllowedCommands(List<String> allowedCommands) { this.allowedCommands = allowedCommands; }
        public Map<String, String> getEnvironment() { return environment; }
        public void setEnvironment(Map<String, String> environment) { this.environment = environment; }
        public Duration getStartupTimeout() { return startupTimeout; }
        public void setStartupTimeout(Duration startupTimeout) { this.startupTimeout = startupTimeout; }
        public Duration getRequestTimeout() { return requestTimeout; }
        public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    }
}
