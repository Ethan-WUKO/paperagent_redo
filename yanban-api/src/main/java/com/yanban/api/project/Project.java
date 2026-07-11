package com.yanban.api.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "root_type", nullable = false, length = 32)
    private ProjectRootType rootType;

    @Column(name = "root_path", nullable = false, length = 1024)
    private String rootPath;

    @Column(name = "canonical_root_path", nullable = false, length = 1024)
    private String canonicalRootPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, length = 32)
    private ProjectAccessMode accessMode;

    @Column(name = "include_rules", nullable = false, columnDefinition = "TEXT")
    private String includeRules;

    @Column(name = "ignore_rules", nullable = false, columnDefinition = "TEXT")
    private String ignoreRules;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    @Column(name = "index_version", nullable = false, length = 128)
    private String indexVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    public Project(Long userId, String name, String rootPath, String canonicalRootPath,
                   String includeRules, String ignoreRules) {
        this.userId = userId;
        this.name = name;
        this.rootType = ProjectRootType.LOCAL_SERVER_ROOT;
        this.rootPath = rootPath;
        this.canonicalRootPath = canonicalRootPath;
        this.accessMode = ProjectAccessMode.READ_ONLY;
        this.includeRules = includeRules;
        this.ignoreRules = ignoreRules;
        this.indexVersion = "UNINDEXED";
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public ProjectRootType getRootType() { return rootType; }
    public String getRootPath() { return rootPath; }
    public String getCanonicalRootPath() { return canonicalRootPath; }
    public ProjectAccessMode getAccessMode() { return accessMode; }
    public String getIncludeRules() { return includeRules; }
    public String getIgnoreRules() { return ignoreRules; }
    public Instant getLastIndexedAt() { return lastIndexedAt; }
    public String getIndexVersion() { return indexVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
