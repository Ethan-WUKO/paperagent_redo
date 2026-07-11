package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Fail-closed completion verifier shared by DIRECT, REACT and Plan step runtime calls. */
@Component
public class CompletionVerifier {
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
        EvidenceLedger ledger = observedEvidence(request, raw);
        AgentRuntimeResult observed = raw.withEvidenceLedger(ledger);
        if (request.projectContext() != null) observed = observed.withTrustedEvidenceLedger(ledger);
        CompletionVerification decision = decide(request, observed, ledger, 0);
        return apply(observed, request, decision);
    }

    public AgentRuntimeResult verifyAfterReflection(AgentRuntimeRequest request, AgentRuntimeResult raw,
                                                    CompletionVerification first) {
        EvidenceLedger ledger = observedEvidence(request, raw);
        AgentRuntimeResult observed = raw.withEvidenceLedger(ledger);
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
        if (request.projectContext() != null && !hasCurrentProjectFileEvidence(ledger, request.projectContext().projectId())) {
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
        CandidateChangeSet candidate = candidateFor(request, result, verification);
        return switch (verification.status()) {
            case VERIFIED -> result.withCompletionVerification(verification).withCandidateChangeSet(candidate)
                    .withCoordination(result.selectedStrategy(), AgentStopReason.COMPLETED, "VERIFIED", result.degraded(), result.degradedFrom());
            case PARTIAL -> failure(result, verification, AgentStopReason.PLAN_PARTIAL, "PARTIAL", candidate);
            case INSUFFICIENT_EVIDENCE -> failure(request.projectContext() == null
                            ? result : result.insufficientProjectEvidence(result.evidenceLedger(), request.history().size()), verification,
                    AgentStopReason.PLAN_PARTIAL, "INSUFFICIENT_EVIDENCE", candidate);
            case FAILED -> failure(result, verification, AgentStopReason.RUNTIME_FAILED, "FAILED", candidate);
        };
    }

    private AgentRuntimeResult failure(AgentRuntimeResult result, CompletionVerification verification,
                                       AgentStopReason stopReason, String outcome, CandidateChangeSet candidate) {
        String error = verification.reasons().isEmpty() ? outcome : String.join("; ", verification.reasons());
        return result.asVerifiedFailure(error).withCompletionVerification(verification).withCandidateChangeSet(candidate)
                .withCoordination(result.selectedStrategy(), stopReason, outcome, result.degraded(), result.degradedFrom());
    }

    private CandidateChangeSet candidateFor(AgentRuntimeRequest request, AgentRuntimeResult result,
                                            CompletionVerification verification) {
        if (request.projectContext() == null || verification.status() != CompletionStatus.VERIFIED || !requestsModification(request.userMessage())) return null;
        return result.evidenceLedger().evidence().stream()
                .filter(ref -> ref.sourceType() == EvidenceSourceType.PROJECT && !"manifest".equals(ref.file())
                        && ProjectEvidenceValidator.isTrusted(ref))
                .findFirst()
                .map(ref -> candidateArtifacts.store(request.userId(), request.sessionId(), new CandidateChangeSet(request.projectContext().projectId(), ref.file(), ref.version(),
                        "Candidate Project change; review before applying.", result.assistantContent(), List.of(ref.id()),
                        CandidateChangeStatus.CANDIDATE, CandidateChangeSet.NOT_APPLIED)))
                .orElse(null);
    }

    private EvidenceLedger observedEvidence(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request.projectContext() == null) return merge(result.evidenceLedger(), EvidenceLedger.empty());
        EvidenceLedger trusted = merge(collectCurrentProjectEvidence(request, result), result.trustedEvidenceLedger());
        return projectEvidenceValidator.current(request.userId(), request.projectContext(), trusted);
    }

    private EvidenceLedger collectCurrentProjectEvidence(AgentRuntimeRequest request, AgentRuntimeResult result) {
        if (request == null || request.projectContext() == null) return EvidenceLedger.empty();
        Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        List<ChatMessage> messages = result.messages();
        for (int i = Math.max(0, request.history().size()); i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null || !"tool".equals(message.role()) || !StringUtils.hasText(message.content())) continue;
            EvidenceRef ref;
            try {
                JsonNode node = objectMapper.readTree(message.content());
                if (node.path("projectId").asLong(-1) != request.projectContext().projectId()) continue;
                String path = node.path("relativePath").asText("");
                String version = node.path("hash").asText(node.path("version").asText(""));
                if (!StringUtils.hasText(path) || !StringUtils.hasText(version)) continue;
                String callId = message.toolCallId() == null ? "unknown" : message.toolCallId();
                ref = new EvidenceRef("trusted-tool:" + request.projectContext().projectId() + ":" + path + ":" + version + ":" + callId,
                        EvidenceSourceType.PROJECT, "PROJECT", path, "tool:" + callId, null, version,
                        "current governed project tool observation");
            } catch (Exception ignored) {
                // Untrusted tool text cannot create evidence.
                continue;
            }
            putExact(refs, ref);
        }
        return new EvidenceLedger(List.copyOf(refs.values()));
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

    private boolean requestsModification(String task) {
        if (!StringUtils.hasText(task)) return false;
        String normalized = task.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("(?s).*(patch|modify|change|fix|revise|suggest|修改|修复|建议|补丁).*" );
    }

    private List<String> ids(EvidenceLedger ledger) {
        return ledger.evidence().stream().map(EvidenceRef::id).toList();
    }
}
