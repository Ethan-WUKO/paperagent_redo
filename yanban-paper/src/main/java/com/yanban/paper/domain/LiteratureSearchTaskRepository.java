package com.yanban.paper.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiteratureSearchTaskRepository extends JpaRepository<LiteratureSearchTask, Long> {
    Optional<LiteratureSearchTask> findByIdAndUserId(Long id, Long userId);

    Optional<LiteratureSearchTask> findByIdempotencyKey(String idempotencyKey);

    List<LiteratureSearchTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<LiteratureSearchTask> findByCreatedAtAfterOrderByCreatedAtDesc(Instant createdAt);

    List<LiteratureSearchTask> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(String status,
                                                                                 Instant updatedBefore,
                                                                                 Pageable pageable);

    List<LiteratureSearchTask> findByStatusAndStartedAtBeforeOrderByStartedAtAsc(String status,
                                                                                 Instant startedBefore,
                                                                                 Pageable pageable);
}
