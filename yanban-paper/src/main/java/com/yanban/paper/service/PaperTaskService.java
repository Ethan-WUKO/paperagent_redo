package com.yanban.paper.service;

import com.yanban.core.user.UserAccountPolicy;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.web.PaperProcessRequest;
import com.yanban.paper.web.PaperTaskHistoryResponse;
import com.yanban.paper.web.PaperTaskResponse;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperTaskService {

    private final PaperTaskRepository paperTaskRepository;
    private final PaperTaskArtifactRepository artifactRepository;
    private final PaperStorageService paperStorageService;
    private final PaperOrchestrator paperOrchestrator;
    private final ObjectProvider<UserAccountPolicy> accountPolicy;

    public PaperTaskService(PaperTaskRepository paperTaskRepository,
                            PaperTaskArtifactRepository artifactRepository,
                            PaperStorageService paperStorageService,
                            PaperOrchestrator paperOrchestrator,
                            ObjectProvider<UserAccountPolicy> accountPolicy) {
        this.paperTaskRepository = paperTaskRepository;
        this.artifactRepository = artifactRepository;
        this.paperStorageService = paperStorageService;
        this.paperOrchestrator = paperOrchestrator;
        this.accountPolicy = accountPolicy;
    }

    @Transactional
    public PaperTaskResponse createTask(Long userId, PaperProcessRequest request) {
        MultipartFile file = request.mainTex() == null ? request.file() : request.mainTex();
        validateTexFile(file);
        validateBibFile(request.bibFile());
        UserAccountPolicy policy = accountPolicy.getIfAvailable();
        if (policy != null) {
            long totalBytes = file.getSize() + (request.bibFile() == null ? 0 : request.bibFile().getSize());
            policy.assertCanCreatePaperTask(userId, totalBytes);
        }
        String objectKey = paperStorageService.storeOriginal(userId, file);
        String bibObjectKey = request.bibFile() == null || request.bibFile().isEmpty()
                ? null
                : paperStorageService.storeOriginal(userId, request.bibFile());
        String title = resolveTitle(file);
        PaperTask task = new PaperTask(
                userId,
                title,
                file.getOriginalFilename(),
                objectKey,
                "PENDING",
                request.targetLanguage(),
                "UPLOAD_RECEIVED",
                null
        );
        task.setInputFormat("LATEX");
        task.setMainEntry(file.getOriginalFilename());
        String baseMode = bibObjectKey == null ? "LATEX_ONLY" : "LATEX_BIB";
        task.setMode(Boolean.TRUE.equals(request.literatureOnly()) ? baseMode + "_LITERATURE_ONLY" : baseMode);
        int maxLiteratureCount = normalizeLiteratureCount(request.literatureCount());
        task.setLiteratureCount(maxLiteratureCount);
        task.setLiteratureMinCount(normalizeLiteratureMinCount(request.literatureMinCount(), maxLiteratureCount));
        PaperTask saved = paperTaskRepository.save(task);
        task = saved;
        saveSourceArtifact(task.getId(), "source_tex", objectKey, file.getOriginalFilename());
        if (bibObjectKey != null) {
            saveSourceArtifact(task.getId(), "source_bib", bibObjectKey, request.bibFile().getOriginalFilename());
        }
        startTaskAfterCommit(task.getId());
        return PaperTaskResponse.from(task, request.scoreThreshold(), request.maxRounds(), request.innerMaxAttempts(), task.getLiteratureCount());
    }

    private Integer normalizeLiteratureCount(Integer literatureCount) {
        if (literatureCount == null) return 20;
        return Math.max(1, Math.min(100, literatureCount));
    }

    private Integer normalizeLiteratureMinCount(Integer literatureMinCount, int maxLiteratureCount) {
        int value = literatureMinCount == null ? Math.min(8, maxLiteratureCount) : literatureMinCount;
        return Math.max(1, Math.min(maxLiteratureCount, value));
    }

    private void startTaskAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            paperOrchestrator.startTask(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                paperOrchestrator.startTask(taskId);
            }
        });
    }

    private void validateTexFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传 LaTeX 主文件（.tex）");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".tex")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主文件仅支持 .tex");
        }
    }

    private void validateBibFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return;
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".bib")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考文献文件仅支持 .bib");
        }
    }

    private void saveSourceArtifact(Long taskId, String type, String objectKey, String filename) {
        PaperTaskArtifact artifact = new PaperTaskArtifact(taskId, type, objectKey, 1);
        artifact.setMetadataJson("{\"filename\":\"" + json(filename) + "\"}");
        artifactRepository.save(artifact);
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Transactional(readOnly = true)
    public List<PaperTaskHistoryResponse> listTasks(Long userId) {
        List<PaperTask> userTasks = paperTaskRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (userTasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = userTasks.stream().map(PaperTask::getId).toList();
        Map<Long, List<PaperTaskArtifact>> artifactsByTask = artifactRepository.findByTaskIdInOrderByCreatedAt(taskIds).stream()
                .collect(Collectors.groupingBy(PaperTaskArtifact::getTaskId));
        return userTasks.stream()
                .map(task -> PaperTaskHistoryResponse.from(task, artifactsByTask.getOrDefault(task.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaperTaskResponse getTask(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
        return PaperTaskResponse.from(task, null, null, null, task.getLiteratureCount());
    }

    @Transactional(readOnly = true)
    public Resource downloadResult(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
        List<PaperTaskArtifact> resultArtifacts = downloadableArtifacts(taskId);
        if (!resultArtifacts.isEmpty()) {
            return new ByteArrayResource(zipArtifacts(resultArtifacts));
        }
        if (task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank()) {
            return new ByteArrayResource(paperStorageService.read(task.getFinalObjectKey()));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "论文结果尚未生成");
    }

    public boolean hasDownloadableResult(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return true;
        }
        return task.getFinalObjectKey() != null && !task.getFinalObjectKey().isBlank();
    }

    public String downloadFilename(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
        String base = task.getSourceFilename() == null || task.getSourceFilename().isBlank()
                ? "paper-result"
                : task.getSourceFilename().replaceAll("\\.[^.]+$", "");
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return base + "-artifacts.zip";
        }
        return task.getSourceFilename() == null || task.getSourceFilename().isBlank() ? "polished.tex" : task.getSourceFilename();
    }

    public String downloadContentType(Long userId, Long taskId) {
        PaperTask task = paperTaskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
        if (!downloadableArtifacts(taskId).isEmpty()) {
            return "application/zip";
        }
        return "application/x-tex; charset=UTF-8";
    }

    private List<PaperTaskArtifact> downloadableArtifacts(Long taskId) {
        return artifactRepository.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> downloadableArtifactTypes().contains(artifact.getType()))
                .toList();
    }

    private byte[] zipArtifacts(List<PaperTaskArtifact> artifacts) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (PaperTaskArtifact artifact : artifacts) {
                    zip.putNextEntry(new ZipEntry(filenameForArtifact(artifact)));
                    zip.write(paperStorageService.read(artifact.getObjectKey()));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("打包论文结果失败", ex);
        }
    }

    private String filenameForArtifact(PaperTaskArtifact artifact) {
        String suffix = artifact.getVersion() == null || artifact.getVersion() <= 1 ? "" : "-v" + artifact.getVersion();
        return switch (artifact.getType()) {
            case "polished_tex" -> "polished" + suffix + ".tex";
            case "suggested_bib" -> "suggested" + suffix + ".bib";
            case "suggested_bib_novel" -> "suggested-novel" + suffix + ".bib";
            case "review_report" -> "review-report" + suffix + ".md";
            case "retrieved_literature_json" -> "retrieved-literature" + suffix + ".json";
            case "retrieved_literature_md" -> "retrieved-literature" + suffix + ".md";
            case "source_bib" -> metadataFilename(artifact, "source-bibliography" + suffix + ".bib");
            default -> artifact.getType() + suffix + ".txt";
        };
    }

    private String metadataFilename(PaperTaskArtifact artifact, String fallback) {
        String metadata = artifact.getMetadataJson();
        if (metadata == null || metadata.isBlank()) return fallback;
        String marker = "\"filename\":\"";
        int start = metadata.indexOf(marker);
        if (start < 0) return fallback;
        start += marker.length();
        int end = metadata.indexOf('"', start);
        if (end <= start) return fallback;
        String filename = metadata.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\").trim();
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) return fallback;
        return filename;
    }

    private Set<String> downloadableArtifactTypes() {
        return Set.of("polished_tex", "suggested_bib", "suggested_bib_novel", "review_report", "retrieved_literature_json", "retrieved_literature_md", "source_bib");
    }

    private String resolveTitle(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return "未命名论文任务";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }
}
