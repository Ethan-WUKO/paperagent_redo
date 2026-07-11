package com.yanban.api.project;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yanban.project")
public class ProjectStorageProperties {

    private String localServerRoot;
    /**
     * Local-deployment-only opt-in for binding an existing absolute directory as a read-only
     * Project. Keep disabled for remote or shared deployments.
     */
    private boolean allowLocalAbsoluteProjectFolders;
    private long maxFileBytes = 2L * 1024 * 1024;
    private int maxSearchResults = 100;
    private int maxFiles = 1_000;
    private long maxTotalBytes = 10L * 1024 * 1024;
    private int maxTraversalDepth = 20;

    public String getLocalServerRoot() { return localServerRoot; }
    public void setLocalServerRoot(String localServerRoot) { this.localServerRoot = localServerRoot; }
    public boolean isAllowLocalAbsoluteProjectFolders() { return allowLocalAbsoluteProjectFolders; }
    public void setAllowLocalAbsoluteProjectFolders(boolean allowLocalAbsoluteProjectFolders) {
        this.allowLocalAbsoluteProjectFolders = allowLocalAbsoluteProjectFolders;
    }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public int getMaxSearchResults() { return maxSearchResults; }
    public void setMaxSearchResults(int maxSearchResults) { this.maxSearchResults = maxSearchResults; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public long getMaxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(long maxTotalBytes) { this.maxTotalBytes = maxTotalBytes; }
    public int getMaxTraversalDepth() { return maxTraversalDepth; }
    public void setMaxTraversalDepth(int maxTraversalDepth) { this.maxTraversalDepth = maxTraversalDepth; }
}
