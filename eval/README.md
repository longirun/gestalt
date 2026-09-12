# Eval-каркас (Р45)

Предиктивная метрика v1: тезис-чек «портрет несёт информацию о будущем поведении юзера».
Парные плечи A/B (инъекция PortraitSnapshot против контроля), машинные оракулы, lift на решённых парах.
Задание, рамка и статистика go/no-go — **`docs/spec/eval/28 - ADR eval-каркаса (Р45).md`**;
порядок стадий — `drafts/31 - План реализации eval-каркаса (Р45).md`.

## Запуск

```bash
cp eval/local.properties.example eval/local.properties   # заполнить креды (файл в .gitignore)
./gradlew -p eval run -Pargs=replay                      # E1: слепки out/snapshots/<pointId>.json
./gradlew -p eval run -Pargs=replay                      # повторно: 0 LLM-вызовов (чекпоинты)
```

`replay` (E1): один проход по логу (батч → LLM-экстракция → дедуп → факты в `gestalt_eval` на `:5433`),
при достижении T каждой точки датасета — пересборка слепка и материализация в `out/snapshots/<pointId>.json`.
Инвариант: в `gestalt_eval` попадают только факты из реплик ≤ T текущей точки; повторный прогон
продолжает с чекпоинта (`replay_checkpoints`). Для отладки без живого лога — `source.fixture`
(путь к jsonl) + `dataset.file` на маленьком датасете. Шаги `arms/oracles/report` — стадии E3–E5, заглушки.

## Positive control: LongMemEval (E2-PC, план 31)

```bash
# local.properties: source.lme.file=data/lme/longmemeval_oracle.json (файлы — HF xiaowu0162/longmemeval, вне VCS)
./gradlew -p eval run -Pargs='lme'                                # dataset/points.lme.jsonl (дефолт single-session-user × 30)
./gradlew -p eval run -Pargs='lme single-session-user,multi-session 50'
# dataset.file=dataset/points.lme.jsonl → replay/wcheck как обычно
```

Каждый вопрос — изолированный микромир: `sourceSession = lme-<question_id>` = портрет-проект,
владелец синтетический `user:lme-<question_id>` (USER-scope факты одного владельца шарятся
между проектами — Р19; синтетический владелец не пускает живые USER-факты в слепки вопроса),
вопрос — реплика-вопрос в конце лога (M, слепок M-exclusive).
Калибровка каркаса, не тезис-чек (истина = gt-ответ; C-точек нет — abstention в релизе отсутствует).
Метрики wcheck: `covered` (есть ≥1 valid-факт — слабая) и `answerCovered` (must-ответ анкерован
valid-фактом, подстрока регистронезависимо — честная метрика «Encoding извлекает факты-ответы»;
лексические парафразы считаются промахом).

Требования: JDK 21; живой лог — PG `:5433` только чтение (как в спайке); БД `gestalt_eval` на `:5433`
(один раз, план 31 §2.1); ключ OpenAI-совместимого LLM-роутера. Поглощение кода спайка — ADR 28 §3.1
(`spike/` не трогаем до архивации по Р34).

## Гигиена

Коммитятся только код и обезличенный датасет (`dataset/README.md` — формат точки).
`local.properties`, `data/` (живые выгрузки), `out/` (прогоны, кэш плеча B) — вне VCS (ADR 28 §3.3).
