-- Поле кэша не выражалось контрактом: строка answers пишется только при генерации,
-- кэш-хит = отсутствие записи; cached=true был недостижим (ревью дока 34, P2).
ALTER TABLE answers DROP COLUMN IF EXISTS cached;
