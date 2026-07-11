package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Versioned server-owned wrapper around an otherwise untrusted planner raw JSON payload. */
final class ProjectPlanEnvelope {
    private static final String SCHEMA = "project_plan_envelope_v1";
    private ProjectPlanEnvelope() { }

    static String wrap(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", SCHEMA);
        root.put("plannerRawJson", plannerRawJson == null ? "" : plannerRawJson);
        if (context == null) {
            root.putNull("serverAttestedProjectContext");
        } else {
            ObjectNode trusted = root.putObject("serverAttestedProjectContext");
            trusted.put("projectId", context.projectId());
            trusted.put("capability", "PROJECT_READ");
        }
        return write(json, root);
    }

    static ProjectRuntimeContext restore(ObjectMapper json, String raw, Long userId) {
        if (raw == null || userId == null) return null;
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) return null; // legacy planner JSON is never trusted.
            boolean claimsEnvelope = root.has("schemaVersion") || root.has("serverAttestedProjectContext") || root.has("plannerRawJson");
            if (!claimsEnvelope) return null;
            if (!SCHEMA.equals(root.path("schemaVersion").asText()) || root.size() != 3
                    || !root.path("plannerRawJson").isTextual() || !root.has("serverAttestedProjectContext")) {
                throw new IllegalStateException("Invalid server-owned Project Plan envelope");
            }
            JsonNode trusted = root.get("serverAttestedProjectContext");
            if (trusted == null || trusted.isNull()) return null;
            if (!trusted.isObject() || trusted.size() != 2 || !trusted.path("projectId").canConvertToLong()
                    || trusted.path("projectId").longValue() <= 0 || !"PROJECT_READ".equals(trusted.path("capability").asText())) {
                throw new IllegalStateException("Invalid server-attested Project Plan context");
            }
            return new ProjectRuntimeContext(userId, trusted.path("projectId").longValue());
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid Project Plan envelope", ex); }
    }

    private static String write(ObjectMapper json, ObjectNode root) {
        try { return json.writeValueAsString(root); }
        catch (Exception ex) { throw new IllegalStateException("Cannot persist Project plan envelope", ex); }
    }
}
