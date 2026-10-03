-- Словарь forkType (спека 28 §6.5): категории развилок живой разметки. Источник истины —
-- PG (спека 28 §5.2); пополняется разметчиком (viewer/ensure) индуктивно до планки 15
-- стабильных живых точек; заморозка — enum в коде по итогам E6 (см. спека 28 §6.5).
-- Seed — категории project (спека 28 §6.5). Миграция ре-раннабельна: ON CONFLICT DO NOTHING.

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
