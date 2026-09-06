CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Таблица фактов (канон 16 §2.1-2.3)
CREATE TABLE IF NOT EXISTS facts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            TEXT NOT NULL,
    session_id          TEXT NOT NULL,
    project_id          TEXT,
    fact_domain         TEXT NOT NULL CHECK (fact_domain IN ('WORLD', 'PSYCHE')),
    scope               TEXT NOT NULL CHECK (scope IN ('SESSION', 'PROJECT', 'USER')),
    kind                TEXT NOT NULL CHECK (kind IN ('STATE', 'NARRATIVE', 'EVENT')),
    subject_norm        TEXT NOT NULL,
    predicate_norm      TEXT,
    object_value        TEXT,
    statement           TEXT,
    conditions          JSONB NOT NULL DEFAULT '{}'::jsonb,
    valid_from          TIMESTAMPTZ,
    valid_to            TIMESTAMPTZ,
    evidence            JSONB NOT NULL DEFAULT '[]'::jsonb,
    reinforcement_count INT NOT NULL DEFAULT 1,
    permanence          TEXT NOT NULL DEFAULT 'INFERRED' CHECK (permanence IN ('INFERRED', 'USER_ASSERTED')),
    w                   DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    embedding           vector(1024),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Exact-identity STATE-факта (16 §4.1); object не входит
CREATE INDEX IF NOT EXISTS idx_facts_exact
    ON facts (owner_id, scope, project_id, subject_norm, predicate_norm);

-- Кандидаты split-identity по живому словарю subject-имён
CREATE INDEX IF NOT EXISTS idx_facts_subject_trgm
    ON facts USING gin (subject_norm gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_facts_conditions
    ON facts USING gin (conditions jsonb_path_ops);

-- Слепок (Р19/Р32): пара (owner, project), NULL-проект — дефолтный слепок
CREATE TABLE IF NOT EXISTS portrait_snapshots (
    owner_id   TEXT NOT NULL,
    project_id TEXT,
    snapshot   JSONB NOT NULL,
    cutoff     TIMESTAMPTZ NOT NULL,
    built_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, project_id)
);
