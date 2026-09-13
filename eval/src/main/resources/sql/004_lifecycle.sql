-- Lifecycle экспериментов (план 31 §7.1, решение 2026-09-13): PG — источник истины
-- для всего, что записал прибор; out/ — стираемый дамп. Реестр экспериментов + история
-- прогонов (экономика — вторым заходом) + пер-точечные результаты.

CREATE TABLE IF NOT EXISTS experiments (
    slug            TEXT PRIMARY KEY,
    material        TEXT NOT NULL,
    dataset_ref     TEXT,
    config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- NULL у архивных записей, зарегистрированных до введения реестра (§7.4)
    ingest_fp       TEXT,
    answer_fp       TEXT,
    status          TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS runs (
    id                BIGSERIAL PRIMARY KEY,
    experiment        TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
    stage             TEXT NOT NULL CHECK (stage IN ('replay', 'arms', 'oracles', 'wcheck', 'report')),
    status            TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'done', 'failed', 'interrupted')),
    -- экономика прогона: заполняется writer-проходом (вторым заходом)
    llm_calls         BIGINT,
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    note              TEXT
);

CREATE INDEX IF NOT EXISTS idx_runs_experiment ON runs (experiment, started_at);

-- Ответы плеч (контракт A/B, план 31 §3): пара (experiment, point, arm); run_id — автор
-- записи для каскадного удаления прогона (перезаписанные позже ответы переживают удаление)
CREATE TABLE IF NOT EXISTS answers (
    experiment        TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
    point_id          TEXT NOT NULL,
    arm               TEXT NOT NULL CHECK (arm IN ('a', 'b')),
    answer            TEXT NOT NULL,
    model             TEXT,
    tokens            BIGINT,
    prompt_tokens     BIGINT,
    completion_tokens BIGINT,
    latency_ms        BIGINT,
    cached            BOOLEAN NOT NULL DEFAULT FALSE,
    run_id            BIGINT REFERENCES runs(id) ON DELETE CASCADE,
    at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment, point_id, arm)
);

-- Вердикты оракулов (E4): payload — канонический json Oracles.PointVerdict;
-- a_pass/b_pass/leak вынесены для дешёвых агрегаций (lift, McNemar)
CREATE TABLE IF NOT EXISTS verdicts (
    experiment  TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
    point_id    TEXT NOT NULL,
    a_pass      BOOLEAN,
    b_pass      BOOLEAN,
    leak        BOOLEAN,
    payload     JSONB NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment, point_id)
);

-- W-проверки (E2): payload — канонический json WCheck.PointReport (+status);
-- covered/answer_covered вынесены для агрегаций
CREATE TABLE IF NOT EXISTS wchecks (
    experiment     TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
    point_id       TEXT NOT NULL,
    covered        BOOLEAN,
    answer_covered BOOLEAN,
    payload        JSONB NOT NULL,
    at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment, point_id)
);
