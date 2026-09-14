# Eval-каркас (Р45)

Предиктивная метрика v1: тезис-чек «портрет несёт информацию о будущем поведении юзера».
Парные плечи A/B (инъекция PortraitSnapshot против контроля), машинные оракулы, lift на решённых парах.
Задание, рамка и статистика go/no-go — **[`../docs/spec/eval/28 - ADR eval-каркаса (Р45).md`](../docs/spec/eval/28%20-%20ADR%20eval-%D0%BA%D0%B0%D1%80%D0%BA%D0%B0%D1%81%D0%B0%20%28%D0%A045%29.md)**;
протокол разметки живых точек — **[`../docs/spec/eval/33 - Протокол разметки живых точек (Р45).md`](../docs/spec/eval/33%20-%20Протокол%20разметки%20живых%20точек%20%28Р45%29.md)**;
порядок стадий и рабочий стол — **[`../drafts/31 - Рабочий стол eval-каркаса (Р45).md`](../drafts/31%20-%20Рабочий%20стол%20eval-%D0%BA%D0%B0%D1%80%D0%BA%D0%B0%D1%81%D0%B0%20%28%D0%A045%29.md)**;
схема архитектуры и потока данных — **[`../docs/spec/eval/архитектура-каркаса.puml`](../docs/spec/eval/архитектура-каркаса.puml)**.

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
(путь к jsonl) + `dataset.file` на маленьком датасете. `candidates <sourceSession>` (E2): выгрузка
реплик среза (`source.day-from/to`) в `data/candidates.jsonl` — вход живой разметки и viewer'а.
`validate [dataset]` (спека 33): аудит датасета спан-инвариантами И1–И3 — trigger ⊆ реплика M,
truth ⊆ реплика-якорь `truthMessageId`, маркеры must/mustNot вне окна (W0, M], причинность;
0 LLM/PG, аргумент — путь к jsonl (ретро-прогон архивов без правки properties); вердикты
ok/span-miss/in-window/causality/unanchored (L1 до 33)/skip (LME) → журнал разметки
`out/validate.<датасет>`. `wcheck` (E2): W-проверка точек — факт-истина в слепке M с evidence ≤ W0 (W0 = последняя реплика
вне окна); вердикты valid/in-window/lag/unknown. `arms [limit]` (E3): плечи A/B на триггерах —
A с дайджестом слепка в системном промпте, B контроль; `temperature=0`, отвечающая модель
`llm.answer.*` (fallback `llm.*`), канон ответов — `gestalt_eval.answers` (существующие пары не
перегенерируются). `oracles` (E4): детерминированные must/mustNot-проверки ответов (вхождение
с границами слов, без судей — ярус smoke) → `gestalt_eval.verdicts`; C-точки — leak-гейт.
`report` (E5): сводка → `out/report.md`: pass A/B, lift, точный МакНемар на рассогласованных
парах (α=0.05), leak-veto исключаются из n, both-fail — список для судьи/ручного разбора.
`runs [slug]` — история прогонов эксперимента с экономикой (llm_calls/токены); `delrun <id>` —
каскадное удаление прогона с записанными им артефактами (перезаписанные позже выживают —
авторство по `answers.run_id`).
`all` — весь конвейер подряд (replay → arms → oracles → report).

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

## С нуля (сторонний прогон E2-PC)

Прибор воспроизводим из чистого чекаута: код, схема и LME-датасет точек — в VCS;
руками только пустая БД, LME-сырьё и LLM-ключ.

1. **JDK 21 + PG 16+** (pgvector опционален — SchemaMigrator деградирует без него):
   ```bash
   docker run -d --name gestalt-eval-pg -e POSTGRES_PASSWORD=postgres -p 5433:5433 pgvector/pgvector:pg16
   ```
2. **Роль и БД** (схему внутри поднимет SchemaMigrator при первом прогоне стадии — sql/001..006 в ресурсах):
   ```bash
   psql -h localhost -p 5433 -U postgres -f eval/sql/000_bootstrap.sql
   # креды не по умолчанию: -v eval_user=… -v eval_password=… [-v eval_db=…] — те же, что target.db.* в local.properties
   ```
3. **LME-сырьё**: файл oracle (S) с HF `xiaowu0162/longmemeval` → `eval/data/lme/longmemeval_oracle.json` (haystack-сессии для replay; сами точки — уже в VCS в `dataset/points.lme.jsonl`).
4. **Конфиг**: `cp eval/local.properties.example eval/local.properties`, заполнить `llm.*` (ключ любого OpenAI-совместимого API; можно env `LLM_API_KEY`), `dataset.file=dataset/points.lme.jsonl`.
5. **Проверка и прогон**:
   ```bash
   ./gradlew -p eval run -Pargs=validate   # аудит датасета (спека 33: И1–И3; LME-точки — skip)
   ./gradlew -p eval run -Pargs=all        # replay → arms → oracles → report (out/report.md)
   ```

Живой лог Honcho (`source.db.*`) для синтетики не нужен — только для живой разметки (E2).
Юнит-тесты без БД зелёные: PG-зависимые пропускаются при недоступном `:5433`.

**Что воспроизводится — процедура, не биты.** Смена экстрактора/отвечающей модели = другой
`ingest_fp`/`answer_fp` (Fingerprints, план 31 §7.2) = другой эксперимент с другими числами;
калибровочные цифры (+48 pp, [`../docs/research/27 - Калибровка eval-каркаса на LongMemEval (Р45).md`](../docs/research/27%20-%20Калибровка%20eval-%D0%BA%D0%B0%D1%80%D0%BA%D0%B0%D1%81%D0%B0%20%D0%BD%D0%B0%20LongMemEval%20%28Р45%29.md)) привязаны к конфигурации калибровки. Это свойство
объекта измерения (LLM), не дефект каркаса.

## Lifecycle экспериментов

PG — источник истины всего, что записал прибор. Реестр `experiments` (slug, статус active/archived,
fingerprint'ы `ingest_fp`/`answer_fp`), история прогонов `runs` (стадия, статус, экономика) и
канонические артефакты (`answers`/`verdicts`/`wchecks`/`snapshots` — слепки M/W0 байт-в-байт);
`out/` — стираемый дамп. Смена `ingest_fp` (датасет/экстрактор/extraction-промпт) — новый
эксперимент: старый archived, инжест и чекпоинты с нуля. Смена `answer_fp` (отвечающая модель/
arms-промпт/окно N) — перезапись ответов тем же экспериментом, слепки переиспользуются.

Требования: JDK 21; живой лог — PG `:5433` только чтение (как в спайке); БД `gestalt_eval` на `:5433`
(один раз, план 31 §2.1); ключ OpenAI-совместимого LLM-роутера. Поглощение кода спайка — ADR 28 §3.1
(`spike/` не трогаем до архивации по Р34).

## Гигиена

Коммитятся только код и обезличенный датасет (`dataset/README.md` — формат точки).
`local.properties`, `data/` (живые выгрузки), `out/` (прогоны, кэш плеча B) — вне VCS (ADR 28 §3.3).
