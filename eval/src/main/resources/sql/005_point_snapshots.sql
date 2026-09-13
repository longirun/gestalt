-- Writer-проход §7 (план 31, 2026-09-13): канонизация пер-точечных слепков M/W0
-- (§7.5 — portrait_snapshots хранит только последний слепок на owner+project,
-- пер-точечная история жила в out/snapshots) + стадия wcheck в истории прогонов.

-- wcheck пишет wchecks — артефакт эксперимента; обвязка стадий требует run-записи
ALTER TABLE runs DROP CONSTRAINT IF EXISTS runs_stage_check;
ALTER TABLE runs ADD CONSTRAINT runs_stage_check
    CHECK (stage IN ('replay', 'arms', 'oracles', 'report', 'migrate', 'wcheck'));

-- Пер-точечные слепки replay: payload — канонический json слепка байт-в-байт
-- (TEXT, не JSONB: без нормализации PG — детерминированный дифф между прогонами);
-- run_id — автор для каскадного удаления прогона (перезаписанные позже переживают)
CREATE TABLE IF NOT EXISTS snapshots (
    experiment TEXT NOT NULL REFERENCES experiments(slug) ON DELETE CASCADE,
    point_id   TEXT NOT NULL,
    kind       TEXT NOT NULL CHECK (kind IN ('m', 'w0')),
    payload    TEXT NOT NULL,
    run_id     BIGINT REFERENCES runs(id) ON DELETE CASCADE,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment, point_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_snapshots_run ON snapshots (run_id);
