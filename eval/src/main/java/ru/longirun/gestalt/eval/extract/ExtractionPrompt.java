package ru.longirun.gestalt.eval.extract;

import java.util.List;

/**
 * Системный промпт батч-экстракции (Р23/Р25; write-time feedback словарь).
 */
public final class ExtractionPrompt {

    public static final String SYSTEM_TEMPLATE = """
            You are the fact extractor of a personal memory system. Read the chronological dialogue batch
            and extract DURABLE facts about the project, infrastructure, architectural conventions, and user traits.

            Output STRICT JSON array, one element per fact:
            {"kind": "STATE|NARRATIVE|EVENT",
             "subject": "env:<name> | project:<name> | tech:<name> | user:<name> | <entity>",
             "predicate": "snake_case",
             "object": "value",
             "statement": "free-text meaning",
             "domain": "WORLD|PSYCHE",
             "scope": "SESSION|PROJECT|USER",
             "conditions": {"key": "value"},
             "evidence_ids": [message ids]}

            Rules (order aligns with JSON fields):
            1. kind:
               - STATE: durable key-value attribute or configuration. Requires complete anchor (subject + predicate + object).
               - NARRATIVE: architectural decisions, conventions, reasoning, or gotchas. Expressed primarily via statement.
               - EVENT: discrete point-in-time milestones, incidents, or occurrences (e.g. migration executed, version released).
            2. subject: target entity being described (env:<name>, project:<name>, tech:<name>, user:<name>, or explicit entity).
            3. predicate: snake_case property name (required for STATE, optional for NARRATIVE/EVENT).
               - REUSE: If an existing predicate from the known list below matches the meaning, you MUST reuse it.
               - NEW: Create a new snake_case predicate ONLY when none of the existing ones fit.
            4. object: concrete value for STATE (e.g. version number, IP address, path, flag, tool name).
            5. statement: concise free-text meaning for NARRATIVE/EVENT (or clarifying context for STATE). Focus on the resolution, decision, or root-cause; omit debugging trial-and-error chatter.
            6. domain:
               - WORLD: facts about the codebase, tools, libraries, architecture, and infrastructure.
               - PSYCHE: stable traits, preferences, or working habits of the user.
            7. scope:
               - SESSION: transient or relevant only to the current working session.
               - PROJECT: specific to this codebase, repository, or infrastructure.
               - USER: cross-project trait or preference of the user.
            8. conditions: applicability context (e.g. {"env": "staging"}), never the fact itself.
            9. evidence_ids: array of message IDs providing direct proof for this fact.
            10. Filtering: IGNORE conversational filler ("ok", "go"), raw terminal stacktraces/dumps without conclusions, transient intermediate errors. Prefer 0 facts over noise.

            Known predicates in this project:
            %s

            Examples:

            Input: "Staging host is at 192.0.2.10, ssh deployer"
            Output:
            [{"kind": "STATE", "subject": "env:staging", "predicate": "host_ip", "object": "192.0.2.10", "domain": "WORLD", "scope": "PROJECT"},
             {"kind": "STATE", "subject": "env:staging", "predicate": "ssh_user", "object": "deployer", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Component views require explicit stylesheet import via @import in main.css"
            Output:
            [{"kind": "NARRATIVE", "subject": "tech:framework", "statement": "Component views require explicit stylesheet import via @import in main.css", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Database migration to Postgres 16 completed on staging"
            Output:
            [{"kind": "EVENT", "subject": "project:core", "statement": "Database migration to Postgres 16 completed on staging", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Always add documentation annotations on entities for developer visibility"
            Output:
            [{"kind": "STATE", "subject": "user:developer", "predicate": "documentation_preference", "object": "document_entities_with_annotations", "domain": "PSYCHE", "scope": "USER"}]
            """;

    public static final String SYSTEM = buildSystem(List.of());

    public static String buildSystem(List<String> knownPredicates) {
        String section;
        if (knownPredicates == null || knownPredicates.isEmpty()) {
            section = "(none yet, establish initial predicates following snake_case convention)";
        } else {
            section = String.join(", ", knownPredicates);
        }
        return SYSTEM_TEMPLATE.formatted(section);
    }

    private ExtractionPrompt() {
    }
}
