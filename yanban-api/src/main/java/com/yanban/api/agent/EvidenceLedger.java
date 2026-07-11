package com.yanban.api.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable evidence-reference inventory for a context package. It is not a
 * completion verifier; task finalization remains NOT_IMPLEMENTED here.
 */
public record EvidenceLedger(List<EvidenceRef> evidence) {

    public EvidenceLedger {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Map<String, EvidenceRef> unique = new LinkedHashMap<>();
        for (EvidenceRef ref : evidence) {
            if (ref == null) {
                continue;
            }
            if (unique.putIfAbsent(ref.id(), ref) != null) {
                throw new IllegalArgumentException("duplicate evidence id: " + ref.id());
            }
        }
        evidence = List.copyOf(unique.values());
    }

    public static EvidenceLedger empty() {
        return new EvidenceLedger(List.of());
    }

    public boolean containsAllReferences(Collection<String> evidenceIds) {
        if (evidenceIds == null || evidenceIds.isEmpty()) {
            return false;
        }
        Map<String, EvidenceRef> byId = evidence.stream()
                .collect(java.util.stream.Collectors.toMap(EvidenceRef::id, ref -> ref));
        return evidenceIds.stream().allMatch(byId::containsKey);
    }

}
