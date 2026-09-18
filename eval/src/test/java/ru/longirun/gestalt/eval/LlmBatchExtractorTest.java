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
    void cleansProseBeforeFencedJson() {
        String prosePrefixed = """
                Based on the messages, here are the extracted facts:

                ```json
                [
                  {
                    "kind": "STATE",
                    "subject": "project:stock",
                    "predicate": "build_tool",
                    "object": "gradle",
                    "domain": "WORLD",
                    "scope": "PROJECT"
                  }
                ]
                ```
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(prosePrefixed);
        assertEquals(1, facts.size());
        assertEquals("gradle", facts.getFirst().object());
    }

    @Test
    void cleansProseAroundBareJson() {
        String prose = """
                Based on my analysis of the batch, the facts are:
                [
                  {
                    "kind": "STATE",
                    "subject": "project:stock",
                    "predicate": "build_tool",
                    "object": "gradle",
                    "domain": "WORLD",
                    "scope": "PROJECT"
                  }
                ]
                Let me know if you need more details.
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(prose);
        assertEquals(1, facts.size());
        assertEquals("gradle", facts.getFirst().object());
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
    void parsesStringEvidenceIds() {
        String json = """
                [
                  {
                    "kind": "STATE",
                    "subject": "project:demo-backend",
                    "predicate": "dev_stand_ssh",
                    "object": "developer@192.168.1.50",
                    "domain": "WORLD",
                    "scope": "PROJECT",
                    "evidence_ids": ["3064", "3074", "not-a-number"]
                  }
                ]
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(json);
        assertEquals(1, facts.size());
        assertEquals(List.of(3064L, 3074L), facts.getFirst().evidenceMessageIds());
    }

    @Test
    void recoversDomainWhenModelPutsPsycheInKind() {
        String json = """
                [
                  {
                    "kind": "PSYCHE",
                    "subject": "user:anton",
                    "predicate": "docs_preference",
                    "object": "annotations",
                    "domain": "WORLD",
                    "scope": "USER"
                  },
                  {
                    "kind": "PSYCHE",
                    "subject": "user:anton",
                    "statement": "Prefers terse replies"
                  }
                ]
                """;

        List<ExtractedFact> facts = LlmBatchExtractor.parseResponse(json);
        assertEquals(2, facts.size());
        // явный WORLD при kind="PSYCHE" перезаписан; kind восстановлен эвристикой (есть predicate+object)
        assertEquals("PSYCHE", facts.get(0).domain());
        assertEquals("STATE", facts.get(0).kind());
        // domain отсутствовал (default WORLD) — тоже PSYCHE; без predicate+object kind → NARRATIVE
        assertEquals("PSYCHE", facts.get(1).domain());
        assertEquals("NARRATIVE", facts.get(1).kind());
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
