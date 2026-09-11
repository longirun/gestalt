-- Чекпоинт инжеста (Р36): сессия лога → последняя обработанная реплика.
-- Повторный replay продолжает с чекпоинта, LLM-экстракция обработанных реплик не повторяется.
CREATE TABLE IF NOT EXISTS replay_checkpoints (
    session_id      TEXT PRIMARY KEY,
    last_message_id BIGINT NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
