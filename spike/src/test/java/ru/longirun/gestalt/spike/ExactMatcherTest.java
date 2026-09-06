package ru.longirun.gestalt.spike;

import org.junit.jupiter.api.Test;
import ru.longirun.gestalt.spike.dedup.ExactMatcher;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ExactMatcherTest {

    @Test
    void conditionsOrderDoesNotMatter() {
        Map<String, String> a = new LinkedHashMap<>(Map.of("organization", "orpheus", "project_type", "personal"));
        Map<String, String> b = new LinkedHashMap<>(Map.of("project_type", "personal", "organization", "orpheus"));
        assertEquals(
                ExactMatcher.canonicalKey("user:anton", "USER", null, "user:anton", "git_email", a),
                ExactMatcher.canonicalKey("user:anton", "USER", null, "user:anton", "git_email", b));
    }

    @Test
    void normalizationCollapsesCaseAndSpaces() {
        assertEquals(
                ExactMatcher.canonicalKey("U:Anton ", "USER", null, "  User:Anton", "Git_Email", null),
                ExactMatcher.canonicalKey("u:anton", "user", null, "user:anton", "git_email", null));
    }

    @Test
    void scopeSeparatesIdentity() {
        assertNotEquals(
                ExactMatcher.canonicalKey("user:anton", "USER", null, "user:anton", "git_email", Map.of()),
                ExactMatcher.canonicalKey("user:anton", "PROJECT", "libx", "user:anton", "git_email", Map.of()));
    }
}
