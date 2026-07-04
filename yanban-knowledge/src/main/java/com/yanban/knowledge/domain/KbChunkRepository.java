package com.yanban.knowledge.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbChunkRepository extends JpaRepository<KbChunk, Long> {
    @Query(value = """
            select c.*
            from kb_chunks c
            join kb_documents d on d.id = c.document_id
            where d.status = 'READY'
              and (d.user_id = :userId or d.is_public = true)
              and lower(c.chunk_text) like lower(concat('%', :query, '%'))
            order by c.id asc
            """, nativeQuery = true)
    List<KbChunk> searchAccessibleChunks(@Param("query") String query, @Param("userId") Long userId, Pageable pageable);

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    List<KbChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId, Pageable pageable);

    int countByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
