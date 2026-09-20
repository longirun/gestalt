-- Этап embed (E7, read-time селекция): backfill facts.embedding для отбора top-K
-- дайджеста. Расширяем CHECK стадии прогона — прогон обёртывается в run как остальные.

ALTER TABLE runs DROP CONSTRAINT IF EXISTS runs_stage_check;
ALTER TABLE runs ADD CONSTRAINT runs_stage_check
    CHECK (stage IN ('replay', 'arms', 'oracles', 'wcheck', 'report', 'embed'));
