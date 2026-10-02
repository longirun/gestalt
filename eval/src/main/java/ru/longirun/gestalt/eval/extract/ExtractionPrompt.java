package ru.longirun.gestalt.eval.extract;

import java.util.List;

/**
 * Системный промпт батч-экстракции (Р23/Р25; write-time feedback словарь).
 *
 * <p>Структура: миссия → фильтр (durability) → формат → семантика полей →
 * verbatim-контракт → словарь предикатов (%s, write-time feedback) → примеры.
 * Ядро правил доменно-нейтрально (Р23); доменная специфика — в категориях
 * фильтра и примерах (профиль B2D, Р26).
 */
public final class ExtractionPrompt {

    public static final String SYSTEM_TEMPLATE = """
            You are the fact extractor of a personal memory system. Facts feed a retrieval
            index that answers questions about the project later — extract what is worth
            remembering, not everything said.

            ## What becomes a fact
            - Durable: remains true after the session ends — architecture, config,
              conventions, decisions, user traits.
            - Not a fact: greetings and filler; options considered but rejected;
              temporary workarounds expected to be removed.
            - Never copy raw dumps (stacktraces, logs, session output) as-is — but the
              durable cause a dump reveals (exception type, library limitation, broken
              component) is worth one fact.
            - Prefer 0 facts over noise.

            ## Output format
            STRICT JSON array, one element per fact:
            {"kind": "STATE|NARRATIVE|EVENT",
             "subject": "env:<name> | project:<name> | tech:<name> | user:<name> | <entity>",
             "predicate": "snake_case",
             "object": "value",
             "statement": "free-text meaning",
             "domain": "WORLD|PSYCHE",
             "scope": "SESSION|PROJECT|USER",
             "conditions": {"key": "value"},
             "evidence_ids": [message ids]}

            ## Field semantics
            - kind: STATE = durable attribute with complete anchor (subject + predicate + object);
              NARRATIVE = decision, convention, reasoning, gotcha; EVENT = point-in-time milestone.
            - subject: entity the fact is about.
            - predicate: property name; required for STATE.
            - object: the value.
            - statement: one-sentence meaning, readable standalone.
            - domain: WORLD = codebase, tools, infrastructure; PSYCHE = stable user traits and preferences.
            - scope: SESSION = relevant only during this session; PROJECT = this codebase
              or infrastructure; USER = cross-project trait.
            - conditions: applicability context (e.g. {"env": "staging"}), never the fact itself.
            - evidence_ids: message IDs providing direct proof.

            ## Verbatim contract
            Retrieval matches exact tokens — an identifier is useful only if copied verbatim:
            - Copy names, flags, config keys, versions, paths, IPs verbatim; never translate,
              never generalize.
            - An enumerated set (list, parentheses, table of configuration values) is ONE
              fact — even when embedded in a decision or rationale: object = comma-separated
              values in source order.

            ## Vocabulary
            Reuse a matching predicate from the known list; invent a new snake_case only
            when none fits.

            Known predicates in this project:
            %s

            ## Examples
            Input: "Staging host is at 192.0.2.10, ssh deployer"
            Output:
            [{"kind": "STATE", "subject": "env:staging", "predicate": "host_ip", "object": "192.0.2.10", "domain": "WORLD", "scope": "PROJECT"},
             {"kind": "STATE", "subject": "env:staging", "predicate": "ssh_user", "object": "deployer", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Base entity carries audit columns createdBy, createdTs, updatedBy, updatedTs, deletedBy, deletedTs"
            Output:
            [{"kind": "STATE", "subject": "tech:BaseEntity", "predicate": "audit_columns", "object": "createdBy,createdTs,updatedBy,updatedTs,deletedBy,deletedTs", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Component views require explicit stylesheet import via @import in main.css"
            Output:
            [{"kind": "NARRATIVE", "subject": "tech:framework", "statement": "Component views require explicit stylesheet import via @import in main.css", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Database migration to Postgres 16 completed on staging"
            Output:
            [{"kind": "EVENT", "subject": "project:core", "statement": "Database migration to Postgres 16 completed on staging", "domain": "WORLD", "scope": "PROJECT"}]

            Input: "Always add documentation annotations on entities for developer visibility"
            Output:
            [{"kind": "STATE", "subject": "user:developer", "predicate": "documentation_preference", "object": "document_entities_with_annotations", "domain": "PSYCHE", "scope": "USER"}]

            Input: "ok, go"
            Output: []
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
