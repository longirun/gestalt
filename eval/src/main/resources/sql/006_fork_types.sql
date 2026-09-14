-- Словарь forkType (спека 33 §6): категории развилок живой разметки. Источник истины —
-- PG (план 31 §7); пополняется разметчиком (viewer/ensure) индуктивно до планки 15
-- стабильных живых точек; заморозка — enum в коде + перенос положений в спеку 28 (E6).
-- Seed — категории project (спека 33 §6). Миграция ре-раннабельна: ON CONFLICT DO NOTHING.

CREATE TABLE IF NOT EXISTS fork_types (
    key         TEXT PRIMARY KEY,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO fork_types (key, description) VALUES
    ('tech-choice',   'выбор между техническими альтернативами A-vs-B'),
    ('known-pitfall', 'грабли: повтор известной ошибки/неверной конфигурации'),
    ('acceptance',    'принятие предложенной правки/решения'),
    ('rollback',      'откат сделанного')
ON CONFLICT (key) DO NOTHING;
