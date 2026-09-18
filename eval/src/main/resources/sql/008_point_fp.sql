-- Пер-точечная инвалидация кэша answers (дока 34, trigger-fp): строка валидна, пока
-- point_fp = hash(answer_fp + trigger + позиция точки) совпал с ожидаемым. NULL —
-- строки, записанные до введения колонки: трактуются как stale и перезаписываются
-- (бэкфилла нет — первый live-прогон после reasoning_effort-фикса и так rewrite).
ALTER TABLE answers ADD COLUMN IF NOT EXISTS point_fp TEXT;
