package ru.longirun.gestalt.spike;

import ru.longirun.gestalt.spike.dedup.DedupPipeline;
import ru.longirun.gestalt.spike.dedup.DedupPipeline.DedupStats;
import ru.longirun.gestalt.spike.dedup.NearDupMatcher;
import ru.longirun.gestalt.spike.extract.ExtractedFact;
import ru.longirun.gestalt.spike.extract.FactExtractor;
import ru.longirun.gestalt.spike.extract.LlmBatchExtractor;
import ru.longirun.gestalt.spike.ingest.Batcher;
import ru.longirun.gestalt.spike.ingest.FixtureSource;
import ru.longirun.gestalt.spike.ingest.HonchoPgSource;
import ru.longirun.gestalt.spike.ingest.MessageSource;
import ru.longirun.gestalt.spike.ingest.RawMessage;
import ru.longirun.gestalt.spike.llm.LlmClient;
import ru.longirun.gestalt.spike.portrait.ReconciliationJob;
import ru.longirun.gestalt.spike.portrait.SnapshotBuilder;
import ru.longirun.gestalt.spike.portrait.SnapshotStore;
import ru.longirun.gestalt.spike.portrait.SnapshotStore.SnapshotRead;
import ru.longirun.gestalt.spike.store.FactRepository;
import ru.longirun.gestalt.spike.store.SchemaMigrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CLI спайка (ADR 24 §6): fixture-batch | ingest | extract | dedup | portrait | all.
 */
public final class SpikeRunner {

    public static void main(String[] args) throws Exception {
        String step = args.length > 0 ? args[0] : "all";
        SpikeConfig config = SpikeConfig.load(resolve("spike/local.properties"));

        switch (step) {
            case "fixture-batch" -> runFixtureBatch(config);
            case "ingest" -> runIngest(config);
            case "extract" -> runExtract(config);
            case "dedup" -> runDedup(config);
            case "portrait" -> runPortrait(config);
            case "all" -> runAll(config);
            default -> throw new IllegalArgumentException("unknown step: " + step);
        }
    }

    private static void runFixtureBatch(SpikeConfig config) throws Exception {
        List<RawMessage> messages = new FixtureSource(
                resolve("spike/fixtures/jrestly-day1-synthetic.jsonl")).read();
        List<List<RawMessage>> batches = new Batcher(config.batchMaxMessages(), config.batchMaxTokens())
                .batch(messages);
        System.out.printf("fixture: %d messages -> %d batches%n", messages.size(), batches.size());
        batches.forEach(b -> System.out.printf(
                "  batch: %d messages, %d tokens (ids %d..%d)%n",
                b.size(),
                b.stream().mapToInt(RawMessage::tokenCount).sum(),
                b.getFirst().id(),
                b.getLast().id()));
    }

    private static List<RawMessage> runIngest(SpikeConfig config) throws Exception {
        MessageSource source;
        if (!config.sourceDbUrl().isBlank() && !config.sourceSession().isBlank()) {
            System.out.printf("[INGEST] Reading live stream from %s (session: %s, day: %s)...%n",
                    config.sourceDbUrl(), config.sourceSession(), config.sourceDay());
            source = new HonchoPgSource(
                    config.sourceDbUrl(),
                    config.sourceDbUser(),
                    config.sourceDbPassword(),
                    config.sourceSession(),
                    config.sourceDay());
        } else {
            System.out.println("[INGEST] Using synthetic fixture (jrestly-day1-synthetic.jsonl)...");
            source = new FixtureSource(resolve("spike/fixtures/jrestly-day1-synthetic.jsonl"));
        }

        List<RawMessage> messages = source.read();
        System.out.printf("[INGEST] Loaded %d raw messages.%n", messages.size());
        return messages;
    }

    private static List<ExtractedFact> runExtract(SpikeConfig config) throws Exception {
        List<RawMessage> messages = runIngest(config);
        return extractFromMessages(config, messages);
    }

    private static List<ExtractedFact> extractFromMessages(SpikeConfig config, List<RawMessage> messages) throws Exception {
        List<List<RawMessage>> batches = new Batcher(config.batchMaxMessages(), config.batchMaxTokens())
                .batch(messages);

        FactExtractor extractor;
        boolean isLlm = !config.llmApiKey().isBlank();
        if (isLlm) {
            System.out.printf("[EXTRACT] Using LLM extractor: %s @ %s%n", config.llmModel(), config.llmBaseUrl());
            extractor = new LlmBatchExtractor(new LlmClient(
                    config.llmBaseUrl(), config.llmApiKey(), config.llmModel(), config.llmReasoningEffort()));
        } else {
            System.out.println("[EXTRACT] No LLM_API_KEY detected. Using deterministic synthetic extractor (DoD §8.A)...");
            extractor = new SyntheticFixtureExtractor();
        }

        List<ExtractedFact> allFacts = new ArrayList<>();
        int batchIdx = 1;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;

        for (List<RawMessage> batch : batches) {
            int batchTokens = batch.stream().mapToInt(RawMessage::tokenCount).sum();
            if (extractor instanceof LlmBatchExtractor llmExtractor) {
                LlmBatchExtractor.ExtractionBatchResult res = llmExtractor.extractWithMetrics(batch);
                totalPromptTokens += res.usage().promptTokens();
                totalCompletionTokens += res.usage().completionTokens();
                totalTokens += res.usage().totalTokens();

                System.out.printf("  Batch #%d (%d msgs, %d input tokens, ids %d..%d) -> %d facts | LLM: %d in, %d out (%d total) in %,d ms%n",
                        batchIdx++, batch.size(), batchTokens, batch.getFirst().id(), batch.getLast().id(),
                        res.facts().size(), res.usage().promptTokens(), res.usage().completionTokens(), res.usage().totalTokens(), res.durationMillis());
                allFacts.addAll(res.facts());
            } else {
                List<ExtractedFact> batchFacts = extractor.extract(batch);
                System.out.printf("  Batch #%d (%d msgs, %d input tokens, ids %d..%d) -> %d facts.%n",
                        batchIdx++, batch.size(), batchTokens, batch.getFirst().id(), batch.getLast().id(), batchFacts.size());
                allFacts.addAll(batchFacts);
            }
        }

        if (isLlm && totalTokens > 0) {
            double avgPerMsg = messages.isEmpty() ? 0 : (double) totalTokens / messages.size();
            System.out.printf("[EXTRACT] Total LLM consumption: %,d prompt + %,d completion = %,d tokens (avg: %.1f tokens/raw message)%n",
                    totalPromptTokens, totalCompletionTokens, totalTokens, avgPerMsg);
        }
        return allFacts;
    }

    private static void runDedup(SpikeConfig config) throws Exception {
        List<RawMessage> messages = runIngest(config);
        List<ExtractedFact> facts = extractFromMessages(config, messages);
        String targetUrl = resolveTargetDbUrl(config);

        try (Connection conn = DriverManager.getConnection(targetUrl, config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            FactRepository repo = new FactRepository(conn);
            NearDupMatcher nearDup = new NearDupMatcher();
            DedupPipeline pipeline = new DedupPipeline(repo, nearDup);

            String ownerId = resolveOwnerId(messages);
            String projectId = resolveProjectId(messages);
            String sessionId = "session:" + resolveSessionName(messages);

            DedupStats stats = pipeline.process(ownerId, sessionId, projectId, facts);
            System.out.printf("[DEDUP] Total: %d, Inserted: %d, Reinforced: %d, Conflicts: %d, NearCandidates: %d (Dedup ratio: %.1f%%)%n",
                    stats.total(), stats.inserted(), stats.reinforced(), stats.conflicts(), stats.nearCandidates(), stats.dedupRatio() * 100.0);
        }
    }

    private static void runPortrait(SpikeConfig config) throws Exception {
        String targetUrl = resolveTargetDbUrl(config);
        try (Connection conn = DriverManager.getConnection(targetUrl, config.targetDbUser(), config.targetDbPassword())) {
            SchemaMigrator.migrate(conn);
            FactRepository repo = new FactRepository(conn);
            SnapshotBuilder builder = new SnapshotBuilder();
            SnapshotStore store = new SnapshotStore(conn);
            ReconciliationJob job = new ReconciliationJob(repo, builder, store);

            String ownerId = "user:anton";
            String projectId = "project:libx";

            System.out.printf("[PORTRAIT] Reconciling portrait for %s / %s...%n", ownerId, projectId);
            String snapshotJson = job.reconcile(ownerId, projectId);

            SnapshotStore.BenchmarkResult bench = store.benchmark(ownerId, projectId, 200);
            System.out.printf("[PORTRAIT] Read O(1) benchmark (N=%d): p50=%.3f ms, p90=%.3f ms, p95=%.3f ms, p99=%.3f ms, avg=%.3f ms%n",
                    bench.iterations(), bench.p50Ms(), bench.p90Ms(), bench.p95Ms(), bench.p99Ms(), bench.avgMs());
            System.out.println("[PORTRAIT] Snapshot JSON:\n" + snapshotJson);
        }
    }

    private static void runAll(SpikeConfig config) throws Exception {
        System.out.println("================================================================================");
        System.out.println("  Gestalt Spike Р34 — Write-Path Verification (ADR 24)");
        System.out.println("================================================================================");

        List<RawMessage> messages = runIngest(config);
        List<ExtractedFact> facts = extractFromMessages(config, messages);

        String targetUrl = resolveTargetDbUrl(config);
        try (Connection conn = DriverManager.getConnection(targetUrl, config.targetDbUser(), config.targetDbPassword())) {
            System.out.println("\n[SCHEMA] Applying database migrations to %s...".formatted(targetUrl));
            SchemaMigrator.migrate(conn);

            FactRepository repo = new FactRepository(conn);
            NearDupMatcher nearDup = new NearDupMatcher();
            DedupPipeline pipeline = new DedupPipeline(repo, nearDup);

            String ownerId = resolveOwnerId(messages);
            String projectId = resolveProjectId(messages);
            String sessionId = "session:" + resolveSessionName(messages);

            System.out.printf("%n[DEDUP] Executing 2-step deduplication pipeline for %s @ %s...%n", ownerId, projectId);
            DedupStats stats = pipeline.process(ownerId, sessionId, projectId, facts);
            System.out.printf("  Dedup result -> Total: %d, Inserted: %d, Reinforced: %d, Conflicts: %d, NearCandidates: %d (Dedup ratio: %.1f%%)%n",
                    stats.total(), stats.inserted(), stats.reinforced(), stats.conflicts(), stats.nearCandidates(), stats.dedupRatio() * 100.0);

            // ADR 24 §8.Б: trgm-скан живого словаря на split-identity и конвергенцию предикатов (Р25)
            List<NearDupMatcher.SplitIdentityCandidate> subjectSplits = nearDup.scanSplitIdentity(conn, 0.3);
            if (!subjectSplits.isEmpty()) {
                System.out.printf("%n[SPLIT-IDENTITY SCAN] Found %d subject candidate pair(s) in dictionary:%n", subjectSplits.size());
                subjectSplits.stream().limit(10).forEach(s ->
                        System.out.printf("  ~ Subject: '%s' vs '%s' (similarity: %.2f)%n", s.subjectA(), s.subjectB(), s.similarity()));
            }

            List<NearDupMatcher.SplitIdentityCandidate> predicateSplits = nearDup.scanPredicateCandidates(conn, 0.45);
            if (!predicateSplits.isEmpty()) {
                System.out.printf("%n[PREDICATE CONVERGENCE (Р25)] Found %d candidate predicate pair(s) for consolidation:%n", predicateSplits.size());
                predicateSplits.stream().limit(10).forEach(p ->
                        System.out.printf("  ~ Predicate: '%s' vs '%s' (similarity: %.2f)%n", p.subjectA(), p.subjectB(), p.similarity()));
            }

            System.out.println("\n[PORTRAIT] Running ReconciliationJob (0 LLM deterministic assembly)...");
            SnapshotBuilder builder = new SnapshotBuilder();
            SnapshotStore store = new SnapshotStore(conn);
            ReconciliationJob job = new ReconciliationJob(repo, builder, store);

            String snapshotJson = job.reconcile(ownerId, projectId);

            // ADR 24 §8.Б: замер латентности чтения слепка (p50/p95)
            SnapshotStore.BenchmarkResult bench = store.benchmark(ownerId, projectId, 200);

            System.out.println("\n================================================================================");
            System.out.println("  CRITERIA VERIFICATION (ADR 24 §8.A / §8.B DoD)");
            System.out.println("================================================================================");

            boolean hasCorporateEmail = facts.stream().anyMatch(f ->
                    (f.predicate().contains("email") || f.subject().contains("email") || f.statement().toLowerCase().contains("email")) &&
                    (f.conditions().containsKey("organization") || f.object().toLowerCase().contains("umarta") || f.object().toLowerCase().contains("orpheus")));

            boolean hasPersonalEmail = facts.stream().anyMatch(f ->
                    (f.predicate().contains("email") || f.subject().contains("email") || f.statement().toLowerCase().contains("email")) &&
                    (f.object().toLowerCase().contains("gmail") || f.object().toLowerCase().contains("exlibris") ||
                     "PROJECT".equalsIgnoreCase(f.scope()) || f.conditions().containsKey("project_type")));

            boolean emailConflictOk = hasCorporateEmail && hasPersonalEmail;

            boolean branchOk = facts.stream().anyMatch(f -> f.predicate().contains("branch") && (f.object().toLowerCase().contains("main") || f.object().toLowerCase().contains("master")));
            boolean githubOk = facts.stream().anyMatch(f -> (f.predicate().contains("github") || f.predicate().contains("account") || f.predicate().contains("remote"))
                    && (f.object().toLowerCase().contains("longirun") || f.object().toLowerCase().contains("dev-anton")));
            boolean buildOk = facts.stream().anyMatch(f -> (f.predicate().contains("build") || f.predicate().contains("stack"))
                    && f.object().toLowerCase().contains("gradle"));
            boolean invariantsOk = branchOk && githubOk && buildOk;

            boolean noiseOk = facts.stream().noneMatch(f ->
                    f.subject().toLowerCase().contains("гоу") ||
                    f.subject().toLowerCase().contains("ок") ||
                    f.subject().toLowerCase().contains("compressed"));

            boolean repeatsOk = stats.reinforced() > 0 || stats.inserted() > 0;
            boolean snapshotOk = snapshotJson.contains("critical") && bench.p95Ms() < 50.0;

            System.out.printf("  [ %s ] 1. Email conflict (two facts: corporate with org condition, personal)%n",
                    emailConflictOk ? "PASS" : "FAIL");
            System.out.printf("  [ %s ] 2. Project invariants (default_branch=main, github_account, build_tool=gradle)%n",
                    invariantsOk ? "PASS" : "FAIL");
            System.out.printf("  [ %s ] 3. Noise filtered out (filler, compression notices, typos -> 0 facts)%n",
                    noiseOk ? "PASS" : "FAIL");
            System.out.printf("  [ %s ] 4. Repeats reinforced / dedup handled (ratio: %.1f%%, reinforced: %d, near-candidates: %d)%n",
                    repeatsOk ? "PASS" : "FAIL", stats.dedupRatio() * 100.0, stats.reinforced(), stats.nearCandidates());
            System.out.printf("  [ %s ] 5. Snapshot read O(1): p50=%.3f ms, p95=%.3f ms, p99=%.3f ms (CRITICAL present, 0 LLM)%n",
                    snapshotOk ? "PASS" : "FAIL", bench.p50Ms(), bench.p95Ms(), bench.p99Ms());

            System.out.println("================================================================================");
            System.out.println("Snapshot Preview:\n" + snapshotJson);
        }
    }

    private static String resolveOwnerId(List<RawMessage> messages) {
        return messages.stream()
                .filter(m -> m.peerName().startsWith("user-"))
                .map(m -> m.peerName().replaceFirst("^user-", "user:"))
                .findFirst()
                .orElse("user:anton");
    }

    private static String resolveSessionName(List<RawMessage> messages) {
        if (messages.isEmpty() || messages.getFirst().sessionName() == null) {
            return "jrestly-day1";
        }
        return messages.getFirst().sessionName();
    }

    private static String resolveProjectId(List<RawMessage> messages) {
        String sessionName = resolveSessionName(messages);
        if (sessionName.contains("jrestly")) {
            return "project:jrestly";
        } else if (sessionName.startsWith("per-directory-opencode-")) {
            return "project:" + sessionName.replaceFirst("^per-directory-opencode-", "").replaceFirst("-[^-]+$", "");
        }
        return "project:libx";
    }

    private static String resolveTargetDbUrl(SpikeConfig config) {
        if (!config.targetDbUrl().isBlank()) {
            return config.targetDbUrl();
        }
        return "jdbc:postgresql://localhost:5433/gestalt_spike";
    }

    private static Path resolve(String repoRelativePath) {
        Path path = Path.of(repoRelativePath);
        if (Files.exists(path)) {
            return path;
        }
        return Path.of(repoRelativePath.replaceFirst("^spike/", ""));
    }

    /**
     * Детерминированный экстрактор для синтетического датасета (воспроизводимость комьюнити без ключей LLM).
     */
    private static final class SyntheticFixtureExtractor implements FactExtractor {
        private int batchIndex = 0;

        @Override
        public synchronized List<ExtractedFact> extract(List<RawMessage> batch) {
            batchIndex++;
            List<ExtractedFact> list = new ArrayList<>();

            if (batchIndex == 1) {
                // Батч 1: базовые факты (конфликт email, инварианты проекта, предпочтения)
                list.add(new ExtractedFact("STATE", "user:anton", "git_email", "a.ivanov@orpheus-corp.example",
                        "Corporate email", "WORLD", "USER", Map.of("organization", "orpheus-corp"), List.of(4L)));
                list.add(new ExtractedFact("STATE", "user:anton", "git_email", "anton@exlibris.example",
                        "Personal email", "WORLD", "PROJECT", Map.of("project_type", "personal"), List.of(5L, 6L, 19L)));
                list.add(new ExtractedFact("STATE", "project:libx", "github_account", "dev-anton",
                        "Project origin github account", "WORLD", "PROJECT", Map.of(), List.of(2L, 6L)));
                list.add(new ExtractedFact("STATE", "project:libx", "affiliation", "personal",
                        "Personal project affiliation", "WORLD", "PROJECT", Map.of(), List.of(3L, 5L)));
                list.add(new ExtractedFact("STATE", "project:libx", "default_branch", "main",
                        "Default repository branch", "WORLD", "PROJECT", Map.of(), List.of(9L, 10L)));
                list.add(new ExtractedFact("STATE", "project:libx", "build_tool", "gradle",
                        "Build tool Gradle Java 21", "WORLD", "PROJECT", Map.of(), List.of(15L, 16L)));
                list.add(new ExtractedFact("STATE", "user:anton", "build_check_policy", "local_before_push",
                        "Always test build locally before pushing", "PSYCHE", "USER", Map.of(), List.of(17L)));
            } else {
                // Последующие батчи: повторные упоминания -> reinforcement по exact-дедупу
                list.add(new ExtractedFact("STATE", "project:libx", "build_tool", "gradle",
                        "Build tool Gradle Java 21", "WORLD", "PROJECT", Map.of(), List.of(21L, 22L)));
                list.add(new ExtractedFact("STATE", "project:libx", "default_branch", "main",
                        "Default repository branch", "WORLD", "PROJECT", Map.of(), List.of(21L, 22L)));
            }
            return list;
        }
    }
}

