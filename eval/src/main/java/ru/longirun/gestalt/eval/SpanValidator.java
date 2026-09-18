package ru.longirun.gestalt.eval;

import ru.longirun.gestalt.eval.ingest.RawMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Валидатор спан-инвариантов И1–И3 (спека 33 §4): машинный аудит того, что билет —
 * проекция живого лога, а не творчество разметчика (галлюцинации generator'а,
 * перефразировка, giveaway в окне). Чистая функция от (точка, сырой лог, окно N),
 * 0 LLM; срабатывает до дорогих стадий (replay жжёт токены экстракции).
 * <p>
 * Инварианты: И1 — verbatim-спаны найдены в якорных репликах (trigger ⊆ реплика M,
 * truth ⊆ реплика truthMessageId); И2 — маркеры ветви (must для L1, mustNot для C)
 * отсутствуют в полуинтервале (W0, M] — реплика W0 исключена (инжестируется в память
 * — предмет измерения), M включена (ответ в самом вопросе — giveaway); маркер в окне
 * виден плечу B — структурная ничья, гарантированное занижение lift, жёсткий reject
 * со статусом in-window (словарь вердиктов WCheck не плодим). И3 — анкер истины
 * строго после триггера (причинность: предсказание будущего, не пересказ настоящего).
 * И4 — W-проверка: существующая механика WCheck, здесь не дублируется.
 * И5 — маркеры не коллидируют: ни один mustNot не матчится внутри must-маркера
 * (границы слов оракула): любой ответ, содержащий must, обязан содержать и mustNot —
 * pass недостижим, точка мёртвая по построению.
 * <p>
 * Статусы (приоритет по нумерации инвариантов спеки; детали собираются все):
 * ok — инварианты соблюдены; span-miss — И1 (спан не найден в реплике-носителе,
 * включая M/якор вне лог-среза); in-window — И2 (маркер в окне);
 * marker-collision — И5 (mustNot внутри must); causality — И3
 * (якор истины не после M); unanchored — legacy-точка до спеки 33 (L1 без
 * truthMessageId: И1-truth/И3 непроверяемы, И1-trigger/И2 проверяются); skip — LME
 * (вопросы авторов бенчмарка, спан-инварианты неприменимы). C-точки: И1 — только по
 * trigger, И2 — по mustNot, И3 — неприменим (истины нет).
 * <p>
 * Матчинг: спаны — substring по нормализованным строкам (lowercase, ё→е, коллапс
 * пробелов — регистр цитирования не должен валить верbatim-вырезку); маркеры И2 —
 * Oracles.matches (границы слов, срез markdown) — та же семантика, что у оракула
 * ответов: валидатор не должен пропускать маркер, который оракул потом засчитает.
 */
public final class SpanValidator {

    public static final String OK = "ok";
    public static final String SPAN_MISS = "span-miss";
    public static final String IN_WINDOW = "in-window";
    public static final String MARKER_COLLISION = "marker-collision";
    public static final String CAUSALITY = "causality";
    public static final String UNANCHORED = "unanchored";
    public static final String SKIP = "skip";

    /** Вердикт на точку для журнала разметки: статус + все найденные нарушения. */
    public record PointVerdict(String pointId, String status, List<String> details) {
    }

    private SpanValidator() {
    }

    /**
     * @param point      точка датасета;
     * @param log        сырой лог сессии точки (тот же срез, что читают replay/wcheck/arms);
     * @param windowSize окно N из конфига прибора;
     * @param lme        LME-точка (sourceSession вида lme-&lt;qid&gt;): спан-инварианты skip (спека 33 §1)
     */
    public static PointVerdict validate(EvalPoint point, List<RawMessage> log, int windowSize, boolean lme) {
        List<String> details = new ArrayList<>();
        if (lme) {
            return new PointVerdict(point.id(), SKIP, details);
        }

        int idxM = EvalRunner.indexOfMessage(log, point.sourceMessageId());
        if (idxM < 0) {
            details.add("message %d (M) not found in the log slice".formatted(point.sourceMessageId()));
            return new PointVerdict(point.id(), SPAN_MISS, details);
        }

        List<String> spanMisses = new ArrayList<>();
        List<String> inWindow = new ArrayList<>();
        List<String> causality = new ArrayList<>();
        boolean unanchored = false;

        String mContent = log.get(idxM).content();
        // пустой/null-спан — не валидная вырезка: contains("") всегда true, потому
        // явный isBlank-чек (ловля ревью 2026-09-14, п.1.2)
        if (point.trigger() == null || point.trigger().isBlank()
                || !normalized(mContent).contains(normalized(point.trigger()))) {
            spanMisses.add("trigger not found as a substring of message %d (M)"
                    .formatted(point.sourceMessageId()));
        }

        boolean control = "C".equals(point.level());
        if (!control) {
            if (point.truthMessageId() == null) {
                unanchored = true;
            } else {
                if (point.truthMessageId() <= point.sourceMessageId()) {
                    causality.add("truth anchor %d is not after M %d"
                            .formatted(point.truthMessageId(), point.sourceMessageId()));
                }
                int idxCarrier = EvalRunner.indexOfMessage(log, point.truthMessageId());
                RawMessage carrier = idxCarrier >= 0 ? log.get(idxCarrier) : null;
                if (carrier == null) {
                    spanMisses.add("message %d (truth anchor) not found in the log slice"
                            .formatted(point.truthMessageId()));
                } else if (point.truth() == null || point.truth().isBlank()
                        || !normalized(carrier.content()).contains(normalized(point.truth()))) {
                    spanMisses.add("truth not found as a substring of message %d"
                            .formatted(point.truthMessageId()));
                }
            }
        }

        // must/mustNot могут отсутствовать в jsonl точки — null-safe списки (ревью 1.3)
        List<String> musts = point.must() == null ? List.<String>of() : point.must();
        List<String> mustNots = point.mustNot() == null ? List.<String>of() : point.mustNot();
        List<String> markers = control ? mustNots : musts;
        int from = Math.max(0, idxM - windowSize);
        for (String marker : markers) {
            for (int i = from; i <= idxM; i++) {
                if (Oracles.matches(log.get(i).content(), marker)) {
                    inWindow.add("marker '%s' found in message %d (window)"
                            .formatted(marker, log.get(i).id()));
                    break;
                }
            }
        }

        // И5: вложенный mustNot наследует границы слов must-вхождения в ответе —
        // любой ответ, засчитанный по must, триггерит mustNot (veto), pass недостижим.
        // Обратная вложенность (must внутри mustNot) pass не ломает: mustNot может
        // не встретиться в ответе вовсе.
        List<String> collisions = new ArrayList<>();
        for (String not : mustNots) {
            for (String m : musts) {
                if (Oracles.matches(m, not)) {
                    collisions.add("mustNot marker '%s' matches inside must marker '%s'"
                            .formatted(not, m));
                }
            }
        }

        details.addAll(spanMisses);
        details.addAll(inWindow);
        details.addAll(collisions);
        details.addAll(causality);
        String status = !spanMisses.isEmpty() ? SPAN_MISS
                : !inWindow.isEmpty() ? IN_WINDOW
                : !collisions.isEmpty() ? MARKER_COLLISION
                : !causality.isEmpty() ? CAUSALITY
                : unanchored ? UNANCHORED
                : OK;
        return new PointVerdict(point.id(), status, details);
    }

    /** Нормализация спана и реплики: lowercase, ё→е, коллапс пробелов — допущение против
     *  регистра/внутренней вёрстки цитирования, не меняющее токены вырезки. */
    private static String normalized(String s) {
        return s == null ? "" : s.toLowerCase().replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }

}
