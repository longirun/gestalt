package ru.longirun.gestalt.eval.portrait;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Diff слепков по id факта (план 31 §2.9): факты монотонны — только добавляются,
 * поэтому появление id в следующем слепке = рождение факта в интервале между пробами.
 */
public final class SnapshotDiff {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String[] SECTIONS = {"critical", "constructs", "preferences"};

    public record FactRef(UUID id, String section, String subject, String predicate,
                          String object, String statement) {
    }

    private SnapshotDiff() {
    }

    /** Факты, отсутствующие в before и присутствующие в after. */
    public static List<FactRef> newFacts(String beforeJson, String afterJson) {
        Map<UUID, FactRef> before = factIndex(beforeJson);
        List<FactRef> appeared = new ArrayList<>();
        for (FactRef fact : factIndex(afterJson).values()) {
            if (!before.containsKey(fact.id())) {
                appeared.add(fact);
            }
        }
        return appeared;
    }

    private static Map<UUID, FactRef> factIndex(String snapshotJson) {
        Map<UUID, FactRef> index = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(snapshotJson);
            for (String section : SECTIONS) {
                for (JsonNode fact : root.path(section)) {
                    UUID id = UUID.fromString(fact.path("id").asText());
                    index.put(id, new FactRef(
                            id, section,
                            fact.path("subject").asText(null),
                            fact.path("predicate").asText(null),
                            fact.path("object").asText(null),
                            fact.path("statement").asText(null)));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse snapshot: " + e.getMessage(), e);
        }
        return index;
    }
}
