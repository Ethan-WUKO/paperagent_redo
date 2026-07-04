package com.yanban.paper.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.paper.storage")
public class PaperStorageProperties {

    private String bucket = "yanban-agent";
    private String objectPrefix = "paper/originals";
    private boolean preferMinio = true;
    private Path localRoot = Path.of("data", "paper-storage");

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public boolean isPreferMinio() {
        return preferMinio;
    }

    public void setPreferMinio(boolean preferMinio) {
        this.preferMinio = preferMinio;
    }

    public Path getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(Path localRoot) {
        this.localRoot = localRoot;
    }
}
