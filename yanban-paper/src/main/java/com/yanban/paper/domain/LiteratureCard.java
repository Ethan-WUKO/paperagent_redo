package com.yanban.paper.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "literature_cards")
public class LiteratureCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String doi;

    @Column(name = "arxiv_id", length = 128)
    private String arxivId;

    @Column(name = "openalex_id", length = 255)
    private String openAlexId;

    @Column(name = "s2_id", length = 255)
    private String s2Id;

    @Column(name = "title_hash", nullable = false, length = 64)
    private String titleHash;

    @Column(nullable = false)
    private String title;

    private String authors;

    @Column(name = "publication_year")
    private Integer publicationYear;

    @Column(length = 512)
    private String venue;

    @Lob
    @Column(name = "abstract_text")
    private String abstractText;

    @Column(length = 1024)
    private String url;

    @Column(name = "pdf_url", length = 1024)
    private String pdfUrl;

    @Column(name = "citation_count")
    private Integer citationCount;

    @Lob
    @Column(name = "referenced_works_json")
    private String referencedWorksJson;

    @Lob
    @Column(name = "fields_of_study_json")
    private String fieldsOfStudyJson;

    @Lob
    @Column(name = "sources_json")
    private String sourcesJson;

    @Lob
    @Column(name = "analysis_json")
    private String analysisJson;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "analyzed_at")
    private Instant analyzedAt;

    @Column(name = "analysis_model_version", length = 128)
    private String analysisModelVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LiteratureCard() {
    }

    public LiteratureCard(String titleHash, String title) {
        this.titleHash = titleHash;
        this.title = title;
    }

    public Long getId() { return id; }
    public String getDoi() { return doi; }
    public String getArxivId() { return arxivId; }
    public String getOpenAlexId() { return openAlexId; }
    public String getS2Id() { return s2Id; }
    public String getTitleHash() { return titleHash; }
    public String getTitle() { return title; }
    public String getAuthors() { return authors; }
    public Integer getPublicationYear() { return publicationYear; }
    public String getVenue() { return venue; }
    public String getAbstractText() { return abstractText; }
    public String getUrl() { return url; }
    public String getPdfUrl() { return pdfUrl; }
    public Integer getCitationCount() { return citationCount; }
    public String getReferencedWorksJson() { return referencedWorksJson; }
    public String getFieldsOfStudyJson() { return fieldsOfStudyJson; }
    public String getSourcesJson() { return sourcesJson; }
    public String getAnalysisJson() { return analysisJson; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public String getAnalysisModelVersion() { return analysisModelVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setDoi(String doi) { this.doi = blankToNull(doi); }
    public void setArxivId(String arxivId) { this.arxivId = blankToNull(arxivId); }
    public void setOpenAlexId(String openAlexId) { this.openAlexId = blankToNull(openAlexId); }
    public void setS2Id(String s2Id) { this.s2Id = blankToNull(s2Id); }
    public void setTitleHash(String titleHash) { this.titleHash = titleHash; }
    public void setTitle(String title) { this.title = title; }
    public void setAuthors(String authors) { this.authors = authors; }
    public void setPublicationYear(Integer publicationYear) { this.publicationYear = publicationYear; }
    public void setVenue(String venue) { this.venue = venue; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public void setUrl(String url) { this.url = url; }
    public void setPdfUrl(String pdfUrl) { this.pdfUrl = pdfUrl; }
    public void setCitationCount(Integer citationCount) { this.citationCount = citationCount; }
    public void setReferencedWorksJson(String referencedWorksJson) { this.referencedWorksJson = referencedWorksJson; }
    public void setFieldsOfStudyJson(String fieldsOfStudyJson) { this.fieldsOfStudyJson = fieldsOfStudyJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }
    public void setAnalysisJson(String analysisJson) { this.analysisJson = analysisJson; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }
    public void setAnalysisModelVersion(String analysisModelVersion) { this.analysisModelVersion = analysisModelVersion; }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
