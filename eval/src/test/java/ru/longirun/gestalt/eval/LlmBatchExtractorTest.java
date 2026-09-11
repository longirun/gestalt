package ru.longirun.gestalt.eval;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.eval.extract.ExtractedFact;
import ru.longirun.gestalt.eval.extract.LlmBatchExtractor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmBatchExtractorTest {

    @Test
    void parsesRawJsonArray() {
        String json = """
                [
                  {
                    "kind": "STATE",
                    "subject": "project:libx",
                    "predicate": "build_tool",
                    "object": "gradle",
                    "domain": "WORLD",
                    "scope": "PROJECT",
                    "conditions": {"version": "8.5"},
                    "evidence_ids": [12, 13]
                  }
                ]
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(json);
        assertEquals(1, facts.size());
        ExtractedFact fact = facts.getFirst();
        assertEquals("STATE", fact.kind());
        assertEquals("project:libx", fact.subject());
        assertEquals("build_tool", fact.predicate());
        assertEquals("gradle", fact.object());
        assertEquals("WORLD", fact.domain());
        assertEquals("PROJECT", fact.scope());
        assertEquals("8.5", fact.conditions().get("version"));
        assertEquals(List.of(12L, 13L), fact.evidenceMessageIds());
    }

    @Test
    void cleansMarkdownFences() {
        String fenced = """
                ```json
                [
                  {
                    "kind": "STATE",
                    "subject": "user:anton",
                    "predicate": "git_email",
                    "object": "sinyagovsky@gmail.com",
                    "domain": "WORLD",
                    "scope": "USER"
                  }
                ]
                ```
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(fenced);
        assertEquals(1, facts.size());
        assertEquals("sinyagovsky@gmail.com", facts.getFirst().object());
    }

    @Test
    void parsesFactsWrappedInObject() {
        String wrapped = """
                {
                  "facts": [
                    {
                      "kind": "NARRATIVE",
                      "subject": "user:anton",
                      "statement": "Prefers concise communication style",
                      "domain": "PSYCHE",
                      "scope": "USER"
                    }
                  ]
                }
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(wrapped);
        assertEquals(1, facts.size());
        assertEquals("PSYCHE", facts.getFirst().domain());
        assertEquals("Prefers concise communication style", facts.getFirst().statement());
    }

    @Test
    void handlesEmptyAndNoisyResponsesGracefully() {
        assertTrue(LlmBatchExtractor.parseResponse("").isEmpty());
        assertTrue(LlmBatchExtractor.parseResponse("   ").isEmpty());
        assertTrue(LlmBatchExtractor.parseResponse("[]").isEmpty());
        assertTrue(LlmBatchExtractor.parseResponse("{\"facts\": []}").isEmpty());
    }

    @Test
    void normalizesUnknownDomainsAndScopes() {
        String json = """
                [
                  {
                    "kind": "UNKNOWN_KIND",
                    "subject": "project:test",
                    "predicate": "foo",
                    "object": "bar",
                    "domain": "UNKNOWN_DOMAIN",
                    "scope": "UNKNOWN_SCOPE"
                  }
                ]
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(json);
        assertEquals(1, facts.size());
        ExtractedFact f = facts.getFirst();
        assertEquals("STATE", f.kind()); // has predicate and object -> STATE
        assertEquals("WORLD", f.domain()); // fallback -> WORLD
        assertEquals("PROJECT", f.scope()); // fallback -> PROJECT
    }
}
