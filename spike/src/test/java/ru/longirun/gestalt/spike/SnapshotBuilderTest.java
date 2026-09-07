package ru.longirun.gestalt.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.spike.portrait.SnapshotBuilder;
import ru.longirun.gestalt.spike.store.FactRepository.StoredFact;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void partitionsFactsIntoSectionsDeterministically() throws Exception {
        StoredFact criticalEmail = new StoredFact(
                UUID.randomUUID(), "user:anton", "s1", "p1", "WORLD", "USER", "STATE",
                "user:anton", "git_email", "a.ivanov@orpheus.example", "",
                Map.of("organization", "orpheus"), 1, 1.0, OffsetDateTime.now());

        StoredFact preferenceTrait = new StoredFact(
                UUID.randomUUID(), "user:anton", "s1", "p1", "PSYCHE", "USER", "STATE",
                "user:anton", "prefers_branch", "main", "Prefers main branch",
                Map.of(), 3, 1.0, OffsetDateTime.now());

        StoredFact worldConstruct = new StoredFact(
                UUID.randomUUID(), "user:anton", "s1", "p1", "WORLD", "PROJECT", "STATE",
                "project:libx", "build_tool", "gradle", "Java 21 Gradle",
                Map.of(), 5, 1.0, OffsetDateTime.now());

        SnapshotBuilder builder = new SnapshotBuilder();
        String json = builder.build("user:anton", "project:libx", List.of(criticalEmail, preferenceTrait, worldConstruct));

        JsonNode root = MAPPER.readTree(json);
        assertEquals("user:anton", root.get("owner_id").asText());
        assertEquals("project:libx", root.get("project_id").asText());

        assertTrue(root.has("critical"));
        assertTrue(root.has("constructs"));
        assertTrue(root.has("preferences"));

        assertEquals(1, root.get("critical").size());
        assertEquals("git_email", root.get("critical").get(0).get("predicate").asText());

        assertEquals(1, root.get("preferences").size());
        assertEquals("prefers_branch", root.get("preferences").get(0).get("predicate").asText());

        assertEquals(1, root.get("constructs").size());
        assertEquals("build_tool", root.get("constructs").get(0).get("predicate").asText());
    }
}
