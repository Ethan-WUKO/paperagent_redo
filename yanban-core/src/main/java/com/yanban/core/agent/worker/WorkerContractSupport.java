package com.yanban.core.agent.worker;

import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchEvidenceRef;
import com.yanban.core.research.ResearchToolContracts;
import com.yanban.core.tool.ToolDescriptor;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class WorkerContractSupport {
    static final Comparator<ResearchEvidenceRef> EVIDENCE_ORDER = Comparator
            .comparing((ResearchEvidenceRef evidence) -> evidence.projectVersion().value())
            .thenComparing(evidence -> evidence.relativePath().value())
            .thenComparing(evidence -> evidence.range().startLine())
            .thenComparing(evidence -> evidence.range().endLine())
            .thenComparing(evidence -> evidence.fileHash().sha256())
            .thenComparing(evidence -> evidence.parserVersion().value())
            .thenComparing(evidence -> evidence.trustLabel().name());

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@+-]{0,255}$");
    private static final Pattern DRIVE_PATH = Pattern.compile("(?i)(?:^|[^A-Za-z0-9])[a-z]:[\\\\/]");
    private static final Pattern UNC_PATH = Pattern.compile("\\\\\\\\");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?:^|[\\s\\\"'])/(?:home|Users|var|etc|tmp|opt|srv|root|mnt|workspace|usr)(?:/|\\b)");
    private static final Pattern FILE_URI = Pattern.compile("(?i)\\bfile:(?:/{1,3}|\\\\)");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|password|secret|access[_-]?token|authorization)\\s*[:=]\\s*\\S+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{8,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?i)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Pattern REASONING_TRACE = Pattern.compile(
            "(?i)(?:chain[-_ ]of[-_ ]thought|reasoning[-_ ]trace|internal[-_ ]reasoning)\\s*[:=]|<thinking>");
    private static final Set<String> READ_ONLY_TOOLS = ResearchToolContracts.all().stream()
            .filter(contract -> contract.descriptor().sideEffectType() == ToolDescriptor.SideEffectType.READ_ONLY)
            .map(contract -> contract.definition().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private WorkerContractSupport() { }

    static String identifier(String value, String label) {
        if (value == null || !value.matches(IDENTIFIER.pattern())) {
            throw new IllegalArgumentException(label + " must be a portable opaque identifier");
        }
        return value;
    }

    static String safeText(String value, String label, int maxUtf8Bytes, boolean allowEmpty) {
        if (value == null || (!allowEmpty && value.isBlank()) || !value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must be present and normalized");
        }
        if (value.chars().anyMatch(character -> (character >= 0 && character < 32
                && character != '\n' && character != '\r' && character != '\t') || character == 127)) {
            throw new IllegalArgumentException(label + " must not contain control characters");
        }
        int bytes = utf8Bytes(value);
        if (bytes > maxUtf8Bytes) {
            throw new IllegalArgumentException(label + " exceeds its UTF-8 byte limit");
        }
        if (DRIVE_PATH.matcher(value).find() || UNC_PATH.matcher(value).find()
                || UNIX_HOST_PATH.matcher(value).find() || FILE_URI.matcher(value).find()) {
            throw new IllegalArgumentException(label + " must not contain a host absolute path");
        }
        if (CREDENTIAL.matcher(value).find() || BEARER.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()) {
            throw new IllegalArgumentException(label + " must not contain credential material");
        }
        if (REASONING_TRACE.matcher(value).find()) {
            throw new IllegalArgumentException(label + " must not contain a reasoning trace");
        }
        return value;
    }

    static int utf8Bytes(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            return bytes.remaining();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("worker contract text is not valid Unicode", ex);
        }
    }

    static List<ProjectRelativePath> sortedDistinctPaths(List<ProjectRelativePath> paths, int max, String label) {
        if (paths == null || paths.size() > max || paths.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(label + " exceeds its limit or contains null");
        }
        List<ProjectRelativePath> result = paths.stream().distinct()
                .sorted(Comparator.comparing(ProjectRelativePath::value)).toList();
        return List.copyOf(result);
    }

    static List<WorkerMaterialAssignment> sortedDistinctAssignments(List<WorkerMaterialAssignment> assignments,
                                                                     int max) {
        if (assignments == null || assignments.size() > max
                || assignments.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("worker material assignments exceed their limit or contain null");
        }
        List<WorkerMaterialAssignment> result = assignments.stream().distinct().sorted(Comparator
                .comparing((WorkerMaterialAssignment assignment) -> assignment.relativePath().value())
                .thenComparing(WorkerMaterialAssignment::materialType)).toList();
        Set<ProjectRelativePath> paths = new HashSet<>();
        if (result.stream().anyMatch(assignment -> !paths.add(assignment.relativePath()))) {
            throw new IllegalArgumentException("a worker material path cannot have multiple assigned types");
        }
        return List.copyOf(result);
    }

    static List<String> sortedDistinctFindingKeys(List<String> keys, int max) {
        if (keys == null || keys.size() > max || keys.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("allowed finding keys exceed their limit or contain null");
        }
        return List.copyOf(keys.stream().map(key -> identifier(key, "allowed finding key"))
                .distinct().sorted().toList());
    }

    static List<String> sortedDistinctTexts(List<String> values, int maxItems, int maxItemBytes,
                                            String label, boolean requireOne) {
        if (values == null || values.size() > maxItems || (requireOne && values.isEmpty())) {
            throw new IllegalArgumentException(label + " has an invalid item count");
        }
        List<String> result = values.stream().map(value -> safeText(value, label, maxItemBytes, false))
                .distinct().sorted().toList();
        if (requireOne && result.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return List.copyOf(result);
    }

    static List<String> sortedDistinctTools(List<String> tools, int max) {
        if (tools == null || tools.size() > max || tools.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("allowed read tools exceed their limit or contain null");
        }
        List<String> result = tools.stream().map(tool -> identifier(tool, "read tool name"))
                .distinct().sorted().toList();
        if (!READ_ONLY_TOOLS.containsAll(result)) {
            throw new IllegalArgumentException("worker tools must be declared read-only research contracts");
        }
        return List.copyOf(result);
    }

    static List<ResearchEvidenceRef> sortedDistinctEvidence(List<ResearchEvidenceRef> evidence, int max,
                                                             String label) {
        if (evidence == null || evidence.size() > max || evidence.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(label + " exceeds its limit or contains null");
        }
        return List.copyOf(evidence.stream().distinct().sorted(EVIDENCE_ORDER).toList());
    }

    static void requireEvidenceScope(ProjectVersionRef version, List<ProjectRelativePath> materialScope,
                                     List<ResearchEvidenceRef> evidence, String label) {
        Set<ProjectRelativePath> allowed = new HashSet<>(materialScope);
        for (ResearchEvidenceRef item : evidence) {
            if (!version.equals(item.projectVersion())) {
                throw new IllegalArgumentException(label + " must match the assigned Project version");
            }
            if (!allowed.contains(item.relativePath())) {
                throw new IllegalArgumentException(label + " falls outside the assigned material scope");
            }
        }
    }

    static String digest(String domain, Consumer<DigestWriter> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestWriter writer = new DigestWriter(digest);
            writer.field(domain);
            fields.accept(writer);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    static String evidenceKey(List<ResearchEvidenceRef> evidence) {
        return digest("yanban-worker-evidence-key-v1", writer -> evidence.forEach(writer::evidence));
    }

    static final class DigestWriter {
        private final MessageDigest digest;

        private DigestWriter(MessageDigest digest) {
            this.digest = digest;
        }

        void field(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) 0);
        }

        void number(long value) {
            field(Long.toString(value));
        }

        void evidence(ResearchEvidenceRef value) {
            field(value.projectVersion().value());
            field(value.relativePath().value());
            field(value.fileHash().sha256());
            number(value.range().startLine());
            number(value.range().endLine());
            field(value.parserVersion().value());
            field(value.trustLabel().name());
        }

        void budget(WorkerBudget value) {
            number(value.maxInputPaths());
            number(value.maxToolCalls());
            number(value.maxFindings());
            number(value.maxEvidenceRefs());
            number(value.maxBytesInspected());
            number(value.maxSummaryUtf8Bytes());
        }

        void usage(WorkerBudgetUsage value) {
            number(value.inputPaths());
            number(value.toolCalls());
            number(value.findings());
            number(value.evidenceRefs());
            number(value.bytesInspected());
            number(value.summaryUtf8Bytes());
        }
    }
}
