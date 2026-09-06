package ru.longirun.gestalt.spike;

import ru.longirun.gestalt.spike.ingest.Batcher;
import ru.longirun.gestalt.spike.ingest.FixtureSource;
import ru.longirun.gestalt.spike.ingest.RawMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI спайка (ADR 24 §6): fixture-batch | ingest | extract | dedup | portrait | all.
 * Шаги extract/dedup/portrait/all — TODO(Р34): связка LlmBatchExtractor → DedupPipeline →
 * SchemaMigrator/FactRepository → SnapshotBuilder/SnapshotStore; журнал прогона в out/.
 */
public final class SpikeRunner {

    public static void main(String[] args) throws Exception {
        String step = args.length > 0 ? args[0] : "fixture-batch";
        SpikeConfig config = SpikeConfig.load(resolve("spike/local.properties"));

        switch (step) {
            case "fixture-batch" -> runFixtureBatch(config);
            case "ingest" -> throw new UnsupportedOperationException("TODO(Р34): HonchoPgSource + Batcher");
            case "extract" -> throw new UnsupportedOperationException("TODO(Р34): батчи → LlmBatchExtractor");
            case "dedup" -> throw new UnsupportedOperationException("TODO(Р34): DedupPipeline → gestalt_spike");
            case "portrait" -> throw new UnsupportedOperationException("TODO(Р34): SnapshotBuilder + ReconciliationJob");
            case "all" -> throw new UnsupportedOperationException("TODO(Р34): полный прогон срез → отчёт в out/");
            default -> throw new IllegalArgumentException("unknown step: " + step);
        }
    }

    private static void runFixtureBatch(SpikeConfig config) throws Exception {
        List<RawMessage> messages = new FixtureSource(
                resolve("spike/fixtures/jrestly-day1-synthetic.jsonl")).read();
        List<List<RawMessage>> batches = new Batcher(config.batchMaxMessages(), config.batchMaxTokens())
                .batch(messages);
        System.out.printf("fixture: %d messages -> %d batches%n",
                messages.size(), batches.size());
        batches.forEach(b -> System.out.printf(
                "  batch: %d messages, %d tokens (ids %d..%d)%n",
                b.size(),
                b.stream().mapToInt(RawMessage::tokenCount).sum(),
                b.getFirst().id(),
                b.getLast().id()));
    }

    private static Path resolve(String repoRelativePath) {
        Path path = Path.of(repoRelativePath);
        if (Files.exists(path)) {
            return path;
        }
        return Path.of(repoRelativePath.replaceFirst("^spike/", ""));
    }
}
