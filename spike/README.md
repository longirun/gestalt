# Спайк ядра (Р34)

Вертикальный срез write-path: `Ingest → Encoding → Pure PG → PortraitSnapshot`. Задание, границы и критерии приёмки — **`docs/spec/24 - ADR спайка ядра (Р34).md`**.

## Запуск

```bash
cp spike/local.properties.example spike/local.properties   # заполнить креды (файл в .gitignore)
./gradlew -p spike test                                     # детерминированное ядро
./gradlew -p spike run -Pargs=fixture-batch                 # синтетика: батчинг без БД и LLM
./gradlew -p spike run -Pargs=all                           # полный прогон (нужны БД + LLM)
```

Требования: JDK 21, PG-инстанция :5433 (`gestalt_spike` — запись, `honcho_memory` — только чтение), ключ OpenAI-совместимого LLM-роутера.

## Гигиена

Коммитятся только код, SQL, промт и синтетический фикстур. `local.properties`, `data/`, `out/` — вне VCS.
