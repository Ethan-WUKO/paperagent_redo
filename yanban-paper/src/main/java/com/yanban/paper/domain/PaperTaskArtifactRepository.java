package com.yanban.paper.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTaskArtifactRepository extends JpaRepository<PaperTaskArtifact, Long> {
    List<PaperTaskArtifact> findByTaskIdOrderByCreatedAt(Long taskId);

    Optional<PaperTaskArtifact> findFirstByTaskIdAndTypeOrderByVersionDesc(Long taskId, String type);

    List<PaperTaskArtifact> findByTaskIdInOrderByCreatedAt(java.util.Collection<Long> taskIds);
}
