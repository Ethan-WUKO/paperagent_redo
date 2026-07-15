package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fail-closed completion verifier shared by DIRECT, REACT and Plan step runtime calls. */
@Component
public class CompletionVerifier {
    private static final Logger log = LoggerFactory.getLogger(CompletionVerifier.class);
    private final ObjectMapper objectMapper;
    private final ProjectEvidenceValidator projectEvidenceValidator;
    private final CandidateChangeArtifactService candidateArtifacts;

    public CompletionVerifier(ObjectMapper objectMapper, ProjectEvidenceValidator projectEvidenceValidator,
                              CandidateChangeArtifactService candidateArtifacts) {
        this.objectMapper = objectMapper;
        this.projectEvidenceValidator = projectEvidenceValidator;
        this.candidateArtifacts = candidateArtifacts;
    }

    public AgentRuntimeResult verify(AgentRuntimeRequest request, AgentRuntimeResult raw) {
        CandidateArtifactResponse toolArtifact = candidateToolArtifact(request, raw);
        AgentRuntimeResult projected = toolArtifact == null ? raw : raw.withCandidateArtifact(toolArtifact);
        EvidenceLedger ledger = observedEvidence(request, projected);
        AgentRuntimeResult observed = projected.withEvidenceLedger(ledger);
        if (request.projectContext() != null) observed = observed.withTrustedEvidenceLedger(ledger);
        CompletionVerification decision = decide(request, observed, ledger, 0);
        return apply(observed, request, decision);
    }

    public AgentRuntimeResult verifyAfterReflection(AgentRuntimeRequest request, AgentRuntimeResult raw,
                                                    CompletionVerification first) {
        CandidateArtifactResponse toolArtifact = candidateToolArtifact(request, raw);
        AgentRuntimeResult projected = toolArtifact == null ? raw : raw.withCandidateArtifact(toolArtifact);
        EvidenceLedger ledger = observedEvidence(request, projected);
        AgentRuntimeResult observed = projected.withEvidenceLedger(ledger);
        if (request.projectContext() != null) observed = observed.withTrustedEvidenceLedger(ledger);
        CompletionVerification second = decide(request, observed, ledger,
                first == null ? 1 : first.reflectionAttempts() + 1);
        return apply(observed, request, second);
    }

    public CompletionVerification decide(AgentRuntimeRequest request, AgentRuntimeResult result,
                                         EvidenceLedger ledger, int reflectionAttempts) {
        List<String> reasons = new ArrayList<>();
        boolean budgetStopped = result.runtimeStopSignal() != AgentRuntimeStopSignal.NONE;
        boolean toolFailure = request.projectContext() != null && result.toolTrace().stream().map(value -> value == null ? "" : value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(value -> value.contains("success=false") || value.contains("tool_error"));
        if (budgetStopped) {
            reasons.add("runtime budget stop: " + result.runtimeStopSignal());
            return new CompletionVerification(CompletionStatus.PARTIAL, reasons, ids(ledger), false, reflectionAttempts);
        }
        if (request.strategy() == AgentStrategy.PLAN_EXECUTE && request.planId() == null && result.planId() != null) {
            return new CompletionVerification(CompletionStatus.PARTIAL, List.of("plan was created but has not been executed"),
                    ids(ledger), false, reflectionAttempts);
        }
        if (!result.success()) {
            CompletionStatus status = switch (StringUtils.hasText(result.outcome()) ? result.outcome() : "") {
                case "INSUFFICIENT_EVIDENCE" -> CompletionStatus.INSUFFICIENT_EVIDENCE;
                case "PARTIAL", "PAUSED", "WAITING", "BUDGET_STOP" -> CompletionStatus.PARTIAL;
                default -> CompletionStatus.FAILED;
            };
            reasons.add(StringUtils.hasText(result.errorMessage()) ? result.errorMessage() : "runtime did not succeed");
            return new CompletionVerification(status, reasons, ids(ledger),
                    (status == CompletionStatus.INSUFFICIENT_EVIDENCE || status == CompletionStatus.PARTIAL)
                            && reflectionAttempts == 0, reflectionAttempts);
        }
        if (toolFailure) {
            reasons.add("at least one governed tool call failed");
            return new CompletionVerification(CompletionStatus.PARTIAL, reasons, ids(ledger), reflectionAttempts == 0, reflectionAttempts);
        }
        if (request.projectContext() != null && requiresProjectFileEvidence(request.userMessage())
                && !hasCurrentProjectFileEvidence(ledger, request.projectContext().projectId())) {
            reasons.add("no current authorized Project file evidence for projectId=" + request.projectContext().projectId());
            return new CompletionVerification(CompletionStatus.INSUFFICIENT_EVIDENCE, reasons, ids(ledger),
                    reflectionAttempts == 0, reflectionAttempts);
        }
        if (!StringUtils.hasText(result.assistantContent())) {
            reasons.add("runtime returned no completion content");
            return new CompletionVerification(CompletionStatus.PARTIAL, reasons, ids(ledger), reflectionAttempts == 0, reflectionAttempts);
        }
        return new CompletionVerification(CompletionStatus.VERIFIED, List.of("runtime outcome and required evidence verified"),
                ids(ledger), false, reflectionAttempts);
    }

    private AgentRuntimeResult apply(AgentRuntimeResult result, AgentRuntimeRequest request, CompletionVerification verification) {
        CandidateArtifactResponse candidate = candidateFor(request, result, verification);
        return switch (verification.status()) {
            case VERIFIED -> result.withCompletionVerification(verification).withCandidateArtifact(candidate)
                    .withCoordination(result.selectedStrategy(), AgentStopReason.COMPLETED, "VERIFIED", result.degraded(), result.degradedFrom());
            case PARTIAL -> controlledBudgetPartial(request, result)
                    ? controlledPartial(result, verification, candidate,
                            AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED)
                    : controlledEvidencePartial(request, result)
                    ? controlledPartial(result, verification, candidate, AgentStopReason.PLAN_PARTIAL)
                    : failure(result, verification, AgentStopReason.PLAN_PARTIAL, "PARTIAL", candidate);
            case INSUFFICIENT_EVIDENCE -> failure(request.projectContext() == null
                            ? result : result.insufficientProjectEvidence(result.evidenceLedger(), request.history().size()), verification,
                    AgentStopReason.PLAN_PARTIAL, "INSUFFICIENT_EVIDENCE", candidate);
            case FAILED -> failure(result, verification, AgentStopReason.RUNTIME_FAILED, "FAILED", candidate);
        };
    }

    private boolean controlledBudgetPartial(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (result.runtimeStopSignal() != AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED
                || !result.success() || !StringUtils.hasText(result.assistantContent())) {
            return false;
        }
        return request.projectContext() == null
                || !requiresProjectFileEvidence(request.userMessage())
                || hasCurrentProjectFileEvidence(result.evidenceLedger(), request.projectContext().projectId());
    }

    private boolean controlledEvidencePartial(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (!result.success() || !StringUtils.hasText(result.assistantContent()) || request.projectContext() == null) {
            return false;
        }
        return !requiresProjectFileEvidence(request.userMessage())
                || hasCurrentProjectFileEvidence(result.evidenceLedger(), request.projectContext().projectId());
    }

    private AgentRuntimeResult controlledPartial(AgentRuntimeResult result,
                                                 CompletionVerification verification,
                                                 CandidateArtifactResponse candidate,
                                                 AgentStopReason stopReason) {
        return result.asControlledPartial().withCompletionVerification(verification).withCandidateArtifact(candidate)
                .withCoordination(result.selectedStrategy(), stopReason, "PARTIAL",
                        true, result.degradedFrom() == null ? result.selectedStrategy() : result.degradedFrom());
    }

    private AgentRuntimeResult failure(AgentRuntimeResult result, CompletionVerification verification,
                                       AgentStopReason stopReason, String outcome, CandidateArtifactResponse candidate) {
        String error = verification.reasons().isEmpty() ? outcome : String.join("; ", verification.reasons());
        return result.asVerifiedFailure(error).withCompletionVerification(verification).withCandidateArtifact(candidate)
                .withCoordination(result.selectedStrategy(), stopReason, outcome, result.degraded(), result.degradedFrom());
    }

    private CandidateArtifactResponse candidateFor(AgentRuntimeRequest request, AgentRuntimeResult result,
                                                    CompletionVerification verification) {
        if (request.projectContext() == null) {
            return null;
        }
        if (result.candidateArtifact() != null) {
            return result.candidateArtifact();
        }
        if (verification.status() != CompletionStatus.VERIFIED || result.candidateIntent() == null) return null;
        try {
            return candidateArtifacts.store(request.userId(), request.sessionId(), request.projectContext(),
                    result.candidateIntent(), result.trustedEvidenceLedger());
        } catch (RuntimeException ex) {
            // Candidate failure is fail-closed and must not rewrite the verified answer or Task outcome.
            log.warn("Structured Candidate intent was rejected projectId={} exceptionType={}",
                    request.projectContext().projectId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    private CandidateArtifactResponse candidateToolArtifact(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request == null || result == null || request.projectContext() == null
                || !request.toolPolicy().allowedTools().contains(ProjectCandidateProposalToolExecutor.TOOL_NAME)) {
            return result == null ? null : result.candidateArtifact();
        }
        Map<String, String> candidateCalls = new LinkedHashMap<>();
        List<ChatMessage> messages = result.messages();
        for (int index = Math.max(0, request.history().size()); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null || message.toolCalls() == null) continue;
            message.toolCalls().stream()
                    .filter(call -> call != null && call.function() != null
                            && ProjectCandidateProposalToolExecutor.TOOL_NAME.equals(call.function().name()))
                    .forEach(call -> candidateCalls.put(call.id(), call.function().name()));
        }
        CandidateArtifactResponse latest = null;
        for (int index = Math.max(0, request.history().size()); index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message == null || !"tool".equals(message.role())
                    || !candidateCalls.containsKey(message.toolCallId()) || !StringUtils.hasText(message.content())) continue;
            try {
                JsonNode projected = objectMapper.readTree(message.content());
                if (!"YANBAN_CANDIDATE_ARTIFACT_V1".equals(projected.path("schemaVersion").asText())
                        || projected.path("artifactId").asLong(-1) < 1
                        || projected.path("projectId").asLong(-1) != request.projectContext().projectId()) continue;
                CandidateArtifactResponse current = candidateArtifacts.getCurrent(
                        request.userId(), projected.path("artifactId").longValue());
                if (current.projectId() == request.projectContext().projectId()) latest = current;
            } catch (RuntimeException ignored) {
                // A failed/legacy tool payload cannot be projected as a Candidate.
            } catch (Exception ignored) {
                // Ordinary text and malformed JSON never become a Candidate.
            }
        }
        return latest == null ? result.candidateArtifact() : latest;
    }

    private EvidenceLedger observedEvidence(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request.projectContext() == null) return merge(result.evidenceLedger(), EvidenceLedger.empty());
        EvidenceLedger trusted = merge(collectCurrentProjectEvidence(request, result),
                merge(request.inheritedTrustedEvidence(), result.trustedEvidenceLedger()));
        return projectEvidenceValidator.current(request.userId(), request.projectContext(), trusted);
    }

    private EvidenceLedger collectCurrentProjectEvidence(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request == null || request.projectContext() == null) return EvidenceLedger.empty();
        Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        List<ChatMessage> messages = result.messages();
        for (int i = Math.max(0, request.history().size()); i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.content())) continue;
            try {
                JsonNode node = objectMapper.readTree(message.content());
                if (node.path("projectId").asLong(-1) != request.projectContext().projectId()) continue;
                String callId = message.toolCallId() == null ? "unknown" : message.toolCallId();
                if (node.path("hits").isArray()) {
                    int hitIndex = 0;
                    for (JsonNode hit : node.path("hits")) {
                        String path = hit.path("relativePath").asText("");
                        String hash = hit.path("hash").asText(hit.path("version").asText(""));
                        int line = hit.path("lineNumber").asInt(0);
                        EvidenceRef ref = attest(request, path, hash, line, line, "project-search@1",
                                callId + ":hit:" + hitIndex++);
                        if (ref != null) putExact(refs, ref);
                    }
                    continue;
                }
                String path = node.path("relativePath").asText("");
                String hash = node.path("hash").asText(node.path("version").asText(""));
                int startLine = node.path("startLine").asInt(0);
                int endLine = node.path("endLine").asInt(0);
                EvidenceRef ref = attest(request, path, hash, startLine, endLine, "project-read-file@1", callId);
                if (ref != null) putExact(refs, ref);
            } catch (Exception ignored) {
                // Untrusted tool text cannot create evidence.
                continue;
            }
        }
        for (EvidenceRef ref : ResearchProjectEvidenceAdapter.extract(objectMapper, messages, request.history().size(),
                request.projectContext(), ResearchProjectEvidenceAdapter.allResearchTools()).evidence()) {
            putExact(refs, ref);
        }
        return new EvidenceLedger(List.copyOf(refs.values()));
    }

    private EvidenceRef attest(AgentRuntimeRequest request, String path, String hash, int startLine,
                               int endLine, String parserVersion, String callId) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(hash) || startLine < 1 || endLine < startLine) return null;
        return projectEvidenceValidator.attestCurrentFile(request.userId(), request.projectContext(),
                "trusted-tool:" + request.projectContext().projectId() + ":" + path + ":" + hash + ":" + callId,
                path, hash, startLine, endLine, parserVersion, "tool:" + callId,
                "current governed project tool observation");
    }

    private EvidenceLedger merge(EvidenceLedger left, EvidenceLedger right) {
        Map<String, EvidenceRef> values = new LinkedHashMap<>();
        if (left != null) left.evidence().forEach(ref -> putExact(values, ref));
        if (right != null) right.evidence().forEach(ref -> putExact(values, ref));
        return new EvidenceLedger(List.copyOf(values.values()));
    }

    private void putExact(Map<String, EvidenceRef> values, EvidenceRef ref) {
        if (ref == null) return;
        EvidenceRef existing = values.putIfAbsent(ref.id(), ref);
        if (existing != null && !existing.equals(ref)) {
            throw new IllegalArgumentException("conflicting evidence id: " + ref.id());
        }
    }

    private boolean hasCurrentProjectFileEvidence(EvidenceLedger ledger, Long projectId) {
        return ledger.evidence().stream().anyMatch(ref -> ref.sourceType() == EvidenceSourceType.PROJECT
                && !"manifest".equals(ref.file()) && StringUtils.hasText(ref.version()) && ProjectEvidenceValidator.isTrusted(ref));
    }

    /**
     * A narrowly-scoped capability inventory describes the server-resolved allow-list, not Project
     * content, so requiring a file observation would produce a false failure. Keep this anchored
     * to tool/capability wording; any request to inspect, analyze, or conclude about Project files
     * continues to require current attested file evidence.
     */
    static boolean requiresProjectFileEvidence(String task) {
        return !isPureProjectCapabilityInquiry(task);
    }

    private static boolean isPureProjectCapabilityInquiry(String task) {
        if (!StringUtils.hasText(task)) {
            return false;
        }
        String normalized = task.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("(?:请|麻烦|帮我|告诉我|说明一下|介绍一下)?\\s*(?:你|当前|现在|本项目助手|这个助手|project)?\\s*(?:现在)?\\s*(?:有哪些|有什么|哪些|列出|介绍|说明|查看)\\s*(?:可用的?)?\\s*(?:工具|能力|功能)\\s*[？?。.!！]*")
                || normalized.matches("(?:please\\s+)?(?:list|show|describe|what\\s+are)\\s+(?:your\\s+|the\\s+)?(?:available\\s+)?(?:tools|capabilities)\\s*[?.!]*");
    }

    private List<String> ids(EvidenceLedger ledger) {
        return ledger.evidence().stream().map(EvidenceRef::id).toList();
    }
}
