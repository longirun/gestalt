package ru.longirun.gestalt.eval.portrait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.longirun.gestalt.eval.store.FactRepository.StoredFact;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Детерминированная сборка слепка, 0 LLM (Р19).
 * Секции: CRITICAL → CONSTRUCTS → PREFERENCES (канон 14 §2);
 * гранулярность (owner_id, project_id) — Р32.
 */
public final class SnapshotBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String build(String ownerId, String projectId, List<StoredFact> facts) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("owner_id", ownerId);
        root.put("project_id", projectId);
        root.put("built_at", Instant.now().toString());

        ArrayNode critical = root.putArray("critical");
        ArrayNode constructs = root.putArray("constructs");
        ArrayNode preferences = root.putArray("preferences");

        List<StoredFact> sorted = facts.stream()
                .sorted(Comparator.comparingInt(StoredFact::reinforcementCount).reversed()
                        .thenComparing(StoredFact::createdAt))
                .toList();

        for (StoredFact fact : sorted) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("id", fact.id().toString());
            item.put("subject", fact.subjectNorm());
            item.put("predicate", fact.predicateNorm());
            item.put("object", fact.objectValue());
            if (fact.statement() != null && !fact.statement().isBlank()) {
                item.put("statement", fact.statement());
            }
            item.put("reinforcement_count", fact.reinforcementCount());
            if (!fact.conditions().isEmpty()) {
                ObjectNode conds = item.putObject("conditions");
                fact.conditions().forEach(conds::put);
            }

            if (isCritical(fact)) {
                critical.add(item);
            } else if ("PSYCHE".equalsIgnoreCase(fact.domain())) {
                preferences.add(item);
            } else {
                constructs.add(item);
            }
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize snapshot: " + e.getMessage(), e);
        }
    }

    private boolean isCritical(StoredFact fact) {
        if (fact.conditions().containsKey("organization") || fact.conditions().containsKey("project_type")) {
            return true;
        }
        String pred = fact.predicateNorm() != null ? fact.predicateNorm().toLowerCase() : "";
        if (pred.contains("email") || pred.contains("identity") || pred.contains("auth") || pred.contains("secret")) {
            return true;
        }
        String stmt = fact.statement() != null ? fact.statement().toLowerCase() : "";
        return stmt.contains("критич") || stmt.contains("critical") || stmt.contains("правило") || stmt.contains("запомни");
    }
}
