package com.yanban.paper.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiteratureCardRepository extends JpaRepository<LiteratureCard, Long> {
    Optional<LiteratureCard> findByDoi(String doi);

    Optional<LiteratureCard> findByArxivId(String arxivId);

    Optional<LiteratureCard> findByOpenAlexId(String openAlexId);

    Optional<LiteratureCard> findByS2Id(String s2Id);

    Optional<LiteratureCard> findFirstByTitleHash(String titleHash);
}
