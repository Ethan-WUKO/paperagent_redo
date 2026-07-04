package com.yanban.paper.service;

import com.yanban.paper.domain.PaperSection;
import com.yanban.paper.domain.PaperSectionRepository;
import com.yanban.paper.domain.PaperTask;
import com.yanban.paper.domain.PaperTaskArtifact;
import com.yanban.paper.domain.PaperTaskArtifactRepository;
import com.yanban.paper.domain.PaperTaskClarification;
import com.yanban.paper.domain.PaperTaskClarificationRepository;
import com.yanban.paper.domain.PaperTaskRepository;
import com.yanban.paper.domain.PaperTaskRound;
import com.yanban.paper.domain.PaperTaskRoundRepository;
import com.yanban.paper.latex.LatexDocument;
import com.yanban.paper.latex.LatexParserService;
import com.yanban.paper.latex.LatexRoleRecognitionService;
import com.yanban.paper.latex.LatexSection;
import com.yanban.paper.latex.RecognizedSectionRole;
import com.yanban.paper.latex.RoleRecognitionResult;
import com.yanban.paper.literature.LiteratureSearchResult;
import com.yanban.paper.literature.LiteratureService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaperOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaperOrchestrator.class);

    private final PaperTaskRepository tasks;
    private final PaperTaskRoundRepository rounds;
    private final PaperSectionRepository sections;
    private final PaperTaskArtifactRepository artifacts;
    private final PaperTaskClarificationRepository clarifications;
    private final PaperEventStreamService eventStreamService;
    private final PaperStorageService paperStorageService;
    private final LatexParserService latexParserService;
    private final LatexRoleRecognitionService roleRecognitionService;
    private final PaperClarificationService clarificationService;
    private final PaperResearchProfileService researchProfileService;
    private final PaperIntroductionAnalysisService introductionAnalysisService;
    private final LiteratureService literatureService;
    private final PaperGapAnalysisService gapAnalysisService;
    private final PaperSectionPolishService sectionPolishService;
    private final PaperAssembleService assembleService;
    private final Executor paperTaskExecutor;
    private final Map<Long, ControlState> controlStates = new ConcurrentHashMap<>();
    private final java.util.Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

    public PaperOrchestrator(PaperTaskRepository tasks,
                             PaperTaskRoundRepository rounds,
                             PaperSectionRepository sections,
                             PaperTaskArtifactRepository artifacts,
                             PaperTaskClarificationRepository clarifications,
                             PaperEventStreamService eventStreamService,
                             PaperStorageService paperStorageService,
                             LatexParserService latexParserService,
                             LatexRoleRecognitionService roleRecognitionService,
                             PaperClarificationService clarificationService,
                             PaperResearchProfileService researchProfileService,
                             PaperIntroductionAnalysisService introductionAnalysisService,
                             LiteratureService literatureService,
                             PaperGapAnalysisService gapAnalysisService,
                             PaperSectionPolishService sectionPolishService,
                             PaperAssembleService assembleService,
                             @Qualifier("paperTaskExecutor") Executor paperTaskExecutor) {
        this.tasks = tasks;
        this.rounds = rounds;
        this.sections = sections;
        this.artifacts = artifacts;
        this.clarifications = clarifications;
        this.eventStreamService = eventStreamService;
        this.paperStorageService = paperStorageService;
        this.latexParserService = latexParserService;
        this.roleRecognitionService = roleRecognitionService;
        this.clarificationService = clarificationService;
        this.researchProfileService = researchProfileService;
        this.introductionAnalysisService = introductionAnalysisService;
        this.literatureService = literatureService;
        this.gapAnalysisService = gapAnalysisService;
        this.sectionPolishService = sectionPolishService;
        this.assembleService = assembleService;
        this.paperTaskExecutor = paperTaskExecutor;
    }

    public void startTask(Long taskId) {
        controlStates.putIfAbsent(taskId, new ControlState());
        if (!runningTasks.add(taskId)) {
            log.info("Paper task {} is already running, skip duplicate start", taskId);
            return;
        }
        paperTaskExecutor.execute(() -> {
            try {
                runTask(taskId);
            } finally {
                runningTasks.remove(taskId);
            }
        });
    }

    @Transactional
    public void pause(Long userId, Long taskId) {
        PaperTask task = getOwnedTask(userId, taskId);
        controlStates.computeIfAbsent(taskId, key -> new ControlState()).paused = true;
        task.setStatus("PAUSED");
        task.setCurrentStage(task.getCurrentStage() == null ? "PAUSED" : task.getCurrentStage());
        eventStreamService.publish(PaperSseEvent.of("paused", taskId, "任务已暂停", task.getCurrentStage()));
    }

    @Transactional
    public void resume(Long userId, Long taskId) {
        PaperTask task = getOwnedTask(userId, taskId);
        ControlState state = controlStates.computeIfAbsent(taskId, key -> new ControlState());
        state.paused = false;
        task.setStatus("RUNNING");
        eventStreamService.publish(PaperSseEvent.of("log", taskId, "任务继续执行", task.getCurrentStage()));
    }

    @Transactional
    public void stop(Long userId, Long taskId) {
        PaperTask task = getOwnedTask(userId, taskId);
        controlStates.computeIfAbsent(taskId, key -> new ControlState()).stopped = true;
        task.setStatus("STOPPED");
        task.setCurrentStage("STOPPED");
        eventStreamService.publish(PaperSseEvent.of("error", taskId, "任务已停止", "STOPPED"));
    }

    private void runTask(Long taskId) {
        try {
            PaperTask task = tasks.findById(taskId).orElseThrow();
            transition(taskId, "RUNNING", "PARSE", null);
            publishProgress("log", taskId, "开始读取 LaTeX 源文件", "PARSE", null, null, null, null, null, 5);
            checkpoint(taskId);

            String texContent = new String(readStorageWithRetry(task.getObjectKey(), "source_tex", taskId), StandardCharsets.UTF_8);
            Map<String, String> bibFiles = readBibFiles(taskId);
            LatexDocument document = latexParserService.parse(task.getMainEntry(), texContent, bibFiles);
            persistRound(taskId, 1, "PARSE", "COMPLETED", task.getMainEntry(), "sections=" + document.sections().size(), "latex parse");
            publishProgress("parse_done", taskId, "LaTeX 解析完成：识别到 " + document.sections().size() + " 个章节", "PARSE", 0, document.sections().size(), null, null, null, 20);

            transition(taskId, "RUNNING", "STRUCTURE_CHECK", null);
            RoleRecognitionResult roles = roleRecognitionService.recognize(document);
            saveSections(task, document, roles);
            publishProgress("sections", taskId, "章节角色识别完成", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 35);
            checkpoint(taskId);

            if (!roles.clarifications().isEmpty()) {
                List<PaperTaskClarification> existingClarifications = clarifications.findByTaskIdOrderByCreatedAtAsc(taskId);
                boolean hasPendingClarification = existingClarifications.stream().anyMatch(item -> "PENDING".equals(item.getStatus()));
                if (existingClarifications.isEmpty()) {
                    clarificationService.createPendingClarifications(taskId, roles.clarifications());
                    if (roles.clarifications().stream().anyMatch(item -> item.blocking())) {
                        persistRound(taskId, 2, "STRUCTURE_CHECK", "WAITING_INPUT", "clarifications", "pending=" + roles.clarifications().size(), "waiting user confirmation");
                        publishProgress("clarification_needed", taskId, "需要确认论文结构后再继续", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 40);
                        return;
                    }
                } else if (hasPendingClarification) {
                    transition(taskId, "WAITING_INPUT", "STRUCTURE_CHECK", null);
                    publishProgress("clarification_needed", taskId, "仍有结构确认问题待回答", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 40);
                    return;
                } else {
                    publishProgress("clarification_resolved", taskId, "结构确认已完成，继续处理", "STRUCTURE_CHECK", 0, document.sections().size(), null, null, null, 45);
                }
            }

            transition(taskId, "RUNNING", "PROFILE", null);
            publishProgress("profile_start", taskId, "开始抽取研究画像", "PROFILE", 0, 1, null, null, null, 50);
            ResearchProfileResult profile = researchProfileService.generateAndSave(taskId, document, task.getTargetLanguage());
            introductionAnalysisService.analyzeAndSave(taskId, document, task.getTargetLanguage());
            persistRound(taskId, 3, "PROFILE", "COMPLETED", "sections=" + document.sections().size(), "keywords=" + profile.keywords(), profile.degraded() ? "degraded" : "model");
            publishProgress("profile_complete", taskId, "研究画像与引言论证骨架完成", "PROFILE", 1, 1, null, null, null, 58);

            transition(taskId, "RUNNING", "RETRIEVE", null);
            publishProgress("retrieve_start", taskId, "按引言 citation slots 检索真实候选文献", "RETRIEVE", 0, 1, null, null, null, 60);
            int literatureLimit = literatureLimit(task);
            int literatureMinLimit = literatureMinLimit(task, literatureLimit);
            int perQueryLimit = Math.max(5, Math.min(20, (int) Math.ceil(literatureLimit / 2.0)));
            List<LiteratureSearchResult> selectedLiterature = literatureService.retrieveForTask(taskId, profile, perQueryLimit, literatureMinLimit, literatureLimit);
            persistRound(taskId, 4, "RETRIEVE", "COMPLETED", "queries=" + profile.keywords(), "selected=" + selectedLiterature.size(), null);
            publishProgress("retrieve_complete", taskId, "文献检索完成，已选择 " + selectedLiterature.size() + " 篇候选文献", "RETRIEVE", selectedLiterature.size(), literatureLimit, null, null, null, 70);
            if (isLiteratureOnly(task)) {
                transition(taskId, "RUNNING", "ASSEMBLE", null);
                publishProgress("assemble_start", taskId, "仅文献推荐模式：生成 recommended bib 与检索报告", "ASSEMBLE", selectedLiterature.size(), literatureLimit, null, null, null, 92);
                assembleService.assemble(taskId, document, false);
                publishProgress("complete", taskId, "文献推荐已完成：未执行 Gap 分析、章节润色和全文改写", "COMPLETE", selectedLiterature.size(), 8, null, null, null, 100);
                return;
            }

            transition(taskId, "RUNNING", "GAP_ANALYSIS", null);
            publishProgress("gap_start", taskId, "开始生成 Gap 分析与建议", "GAP_ANALYSIS", 0, 1, null, null, null, 72);
            List<GapSuggestionResult> gapSuggestions = gapAnalysisService.generateAndSave(taskId, structureSummary(document, roles), task.getTargetLanguage());
            persistRound(taskId, 5, "GAP_ANALYSIS", "COMPLETED", "selectedLiterature=" + selectedLiterature.size(), "suggestions=" + gapSuggestions.size(), null);
            publishProgress("gap_complete", taskId, "Gap 分析完成，生成 " + gapSuggestions.size() + " 条建议", "GAP_ANALYSIS", gapSuggestions.size(), Math.max(1, gapSuggestions.size()), null, null, null, 80);

            transition(taskId, "RUNNING", "POLISH", null);
            publishProgress("polish_start", taskId, "开始分章润色", "POLISH", 0, document.sections().size(), null, null, null, 82);
            int polishedCount = polishSections(taskId, document, task.getTargetLanguage());
            persistRound(taskId, 6, "POLISH", "COMPLETED", "sections=" + document.sections().size(), "processed=" + polishedCount, null);
            publishProgress("polish_complete", taskId, "分章润色完成，处理 " + polishedCount + " 个章节", "POLISH", polishedCount, document.sections().size(), null, null, null, 90);

            transition(taskId, "RUNNING", "ASSEMBLE", null);
            publishProgress("assemble_start", taskId, "开始生成完整产物", "ASSEMBLE", document.sections().size(), document.sections().size(), null, null, null, 92);
            assembleService.assemble(taskId, document, true);
            publishProgress("complete", taskId, "论文任务已完成：已生成润色文本、推荐文献与审查报告", "COMPLETE", document.sections().size(), document.sections().size(), null, null, null, 100);
        } catch (TaskStoppedException ex) {
            transition(taskId, "STOPPED", "STOPPED", ex.getMessage());
            publish("error", taskId, ex.getMessage(), "STOPPED");
        } catch (Exception ex) {
            log.error("Paper task {} failed", taskId, ex);
            transition(taskId, "FAILED", "FAILED", rootMessage(ex));
            publish("error", taskId, "论文任务失败: " + rootMessage(ex), "FAILED");
        }
    }

    private boolean isLiteratureOnly(PaperTask task) {
        return task.getMode() != null && task.getMode().contains("LITERATURE_ONLY");
    }

    private int literatureLimit(PaperTask task) {
        Integer value = task.getLiteratureCount();
        return value == null ? 20 : Math.max(1, Math.min(100, value));
    }

    private int literatureMinLimit(PaperTask task, int maxLimit) {
        Integer value = task.getLiteratureMinCount();
        return value == null ? Math.min(8, maxLimit) : Math.max(1, Math.min(maxLimit, value));
    }

    private Map<String, String> readBibFiles(Long taskId) {
        Map<String, String> bibFiles = new LinkedHashMap<>();
        artifacts.findByTaskIdOrderByCreatedAt(taskId).stream()
                .filter(artifact -> "source_bib".equals(artifact.getType()))
                .forEach(artifact -> {
                    try {
                        bibFiles.put("refs.bib", new String(readStorageWithRetry(artifact.getObjectKey(), "source_bib", taskId), StandardCharsets.UTF_8));
                    } catch (Exception ex) {
                        log.warn("Skip unreadable bib artifact {} for paper task {}", artifact.getObjectKey(), taskId, ex);
                        publishProgress("bib_read_skipped", taskId, "参考文献文件读取失败，已按无 bib 模式继续：" + rootMessage(ex), "PARSE", null, null, null, null, null, null);
                    }
                });
        return bibFiles;
    }

    private byte[] readStorageWithRetry(String objectKey, String label, Long taskId) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                byte[] bytes = paperStorageService.read(objectKey);
                if (bytes.length > 0) {
                    return bytes;
                }
                last = new IllegalStateException(label + " is empty");
            } catch (RuntimeException ex) {
                last = ex;
            }
            log.warn("Read {} for paper task {} failed on attempt {}/3, objectKey={}", label, taskId, attempt, objectKey, last);
            sleep(300L * attempt);
        }
        throw new IllegalStateException("读取论文" + label + "失败，请重新上传或检查本地/MinIO 存储", last);
    }

    private String structureSummary(LatexDocument document, RoleRecognitionResult roles) {
        StringBuilder builder = new StringBuilder();
        builder.append("Sections:\n");
        for (LatexSection section : document.sections()) {
            builder.append("- [").append(section.orderIndex()).append("] ")
                    .append(section.title()).append(" role=").append(section.role()).append('\n');
        }
        if (!roles.clarifications().isEmpty()) {
            builder.append("Clarifications:\n");
            roles.clarifications().forEach(item -> builder.append("- ").append(item.message()).append(" default=").append(item.defaultOption()).append('\n'));
        }
        return builder.toString();
    }

    private int polishSections(Long taskId, LatexDocument document, String targetLanguage) {
        int processed = 0;
        int total = document.sections().size();
        for (LatexSection section : document.sections()) {
            checkpoint(taskId);
            publishProgress("section_polish_start", taskId, "润色章节：" + section.title(), "POLISH", processed, total, section.title(), 1, 1, null);
            try {
                sectionPolishService.polishSection(taskId, section, targetLanguage, 0.7, 1);
            } catch (Exception ex) {
                log.warn("Skip polishing section {} of task {} due to error", section.title(), taskId, ex);
                publishProgress("section_polish_skipped", taskId, "章节润色失败，已保留原文：" + section.title(), "POLISH", processed, total, section.title(), 1, 1, null);
            }
            processed++;
            publishProgress("section_polish_complete", taskId, "章节处理完成：" + section.title(), "POLISH", processed, total, section.title(), 1, 1, null);
        }
        return processed;
    }

    @Transactional
    protected void saveSections(PaperTask task, LatexDocument document, RoleRecognitionResult roles) {
        sections.deleteAll(sections.findByTaskIdOrderByOrderIndexAsc(task.getId()));
        Map<Integer, RecognizedSectionRole> roleByOrder = roles.sectionRoles().stream()
                .collect(Collectors.toMap(RecognizedSectionRole::sectionOrderIndex, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        for (LatexSection latexSection : document.sections()) {
            RecognizedSectionRole recognized = roleByOrder.get(latexSection.orderIndex());
            String originalObjectKey = paperStorageService.storeArtifact(
                    task.getUserId(),
                    "section_original",
                    "section-" + latexSection.orderIndex() + ".tex",
                    latexSection.rawText().getBytes(StandardCharsets.UTF_8),
                    "application/x-tex; charset=UTF-8"
            );
            PaperSection section = new PaperSection(
                    task.getId(),
                    document.sourcePath(),
                    latexSection.orderIndex(),
                    latexSection.level(),
                    latexSection.title(),
                    recognized == null ? latexSection.role().name() : recognized.role().name(),
                    recognized == null ? 0.5 : recognized.confidence(),
                    recognized == null ? "parser" : recognized.source(),
                    latexSection.startOffset(),
                    latexSection.endOffset()
            );
            section.setOriginalObjectKey(originalObjectKey);
            section.setPolishStatus("PENDING");
            sections.save(section);
        }
    }

    private void checkpoint(Long taskId) {
        ControlState state = controlStates.computeIfAbsent(taskId, key -> new ControlState());
        if (state.stopped) {
            throw new TaskStoppedException("任务已被停止");
        }
        while (state.paused) {
            sleep(200);
            if (state.stopped) {
                throw new TaskStoppedException("任务已被停止");
            }
        }
        sleep(200);
    }

    @Transactional
    protected void transition(Long taskId, String status, String stage, String errorMessage) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        task.setStatus(status);
        task.setCurrentStage(stage);
        task.setErrorMessage(errorMessage);
        tasks.save(task);
    }

    @Transactional
    protected void persistRound(Long taskId, int roundNumber, String stage, String status,
                                String inputText, String outputText, String notes) {
        PaperTaskRound round = rounds.findByTaskIdAndRoundNumberAndStage(taskId, roundNumber, stage)
                .orElseGet(() -> new PaperTaskRound(taskId, roundNumber, stage, status, inputText, outputText, notes));
        round.setStatus(status);
        round.setInputText(inputText);
        round.setOutputText(outputText);
        round.setNotes(notes);
        rounds.save(round);
    }

    @Transactional
    protected void transitionComplete(Long taskId, String finalObjectKey) {
        PaperTask task = tasks.findById(taskId).orElseThrow();
        task.setFinalObjectKey(finalObjectKey);
        task.setStatus("COMPLETED");
        task.setCurrentStage("COMPLETE");
        task.setErrorMessage(null);
        tasks.save(task);
    }

    private void publish(String type, Long taskId, String message, String stage) {
        eventStreamService.publish(PaperSseEvent.of(type, taskId, message, stage));
    }

    private void publishProgress(String type,
                                 Long taskId,
                                 String message,
                                 String stage,
                                 Integer currentSection,
                                 Integer totalSections,
                                 String sectionTitle,
                                 Integer attempt,
                                 Integer maxAttempts,
                                 Integer progressPercent) {
        eventStreamService.publish(PaperSseEvent.progress(
                type,
                taskId,
                message,
                stage,
                currentSection,
                totalSections,
                sectionTitle,
                attempt,
                maxAttempts,
                progressPercent
        ));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private PaperTask getOwnedTask(Long userId, Long taskId) {
        return tasks.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "论文任务不存在"));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务线程被中断", ex);
        }
    }

    private static final class ControlState {
        private volatile boolean paused;
        private volatile boolean stopped;
    }

    private static final class TaskStoppedException extends RuntimeException {
        private TaskStoppedException(String message) {
            super(message);
        }
    }
}
