package ru.longirun.gestalt.eval.extract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtractionPromptTest {

    @Test
    void rulesOrderMatchesJsonFields() {
        String template = ExtractionPrompt.SYSTEM_TEMPLATE;

        int posKind = template.indexOf("1. kind:");
        int posSubject = template.indexOf("2. subject:");
        int posPredicate = template.indexOf("3. predicate:");
        int posObject = template.indexOf("4. object:");
        int posStatement = template.indexOf("5. statement:");
        int posDomain = template.indexOf("6. domain:");
        int posScope = template.indexOf("7. scope:");
        int posConditions = template.indexOf("8. conditions:");
        int posEvidence = template.indexOf("9. evidence_ids:");
        int posFilter = template.indexOf("10. Filtering:");

        assertTrue(posKind >= 0 && posKind < posSubject);
        assertTrue(posSubject < posPredicate);
        assertTrue(posPredicate < posObject);
        assertTrue(posObject < posStatement);
        assertTrue(posStatement < posDomain);
        assertTrue(posDomain < posScope);
        assertTrue(posScope < posConditions);
        assertTrue(posConditions < posEvidence);
        assertTrue(posEvidence < posFilter);

        // Verify that kind explains STATE, NARRATIVE, and EVENT
        String kindSection = template.substring(posKind, posSubject);
        assertTrue(kindSection.contains("STATE:"));
        assertTrue(kindSection.contains("NARRATIVE:"));
        assertTrue(kindSection.contains("EVENT:"));
    }

    @Test
    void buildSystemWithEmptyList() {
        String prompt = ExtractionPrompt.buildSystem(List.of());
        assertTrue(prompt.contains("(none yet, establish initial predicates following snake_case convention)"));
        assertEquals(ExtractionPrompt.SYSTEM, prompt);
    }

    @Test
    void buildSystemWithPredicates() {
        List<String> predicates = List.of("host_ip", "ssh_user", "db_host", "auth_model");
        String prompt = ExtractionPrompt.buildSystem(predicates);
        assertTrue(prompt.contains("host_ip, ssh_user, db_host, auth_model"));
        assertFalse(prompt.contains("(none yet"));
    }

    @Test
    void noPrivateDataInTemplate() {
        String tokens = loadForbiddenTokens();
        Assumptions.assumeTrue(tokens != null && !tokens.isBlank(),
                "test.prompt.forbidden-tokens not configured in local.properties, skipping");

        String template = ExtractionPrompt.SYSTEM_TEMPLATE;
        for (String token : tokens.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                assertFalse(template.contains(trimmed),
                        () -> "Template contains forbidden private token: " + trimmed);
            }
        }
    }

    private static String loadForbiddenTokens() {
        Path[] candidates = {
                Path.of("local.properties"),
                Path.of("eval/local.properties")
        };
        for (Path path : candidates) {
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    Properties props = new Properties();
                    props.load(in);
                    return props.getProperty("test.prompt.forbidden-tokens");
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    @Test
    void systemConstantIsStable() {
        assertNotNull(ExtractionPrompt.SYSTEM);
        assertFalse(ExtractionPrompt.SYSTEM.isBlank());
    }
}
