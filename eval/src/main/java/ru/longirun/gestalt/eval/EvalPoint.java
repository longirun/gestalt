package ru.longirun.gestalt.eval;

import java.util.List;

/**
 * Точка датасета — «экзаменационный билет» (ADR 28 §3.2): позиция T в живом логе,
 * триггер, истина-продолжение и машинные проверки. Артефакт ручной разметки;
 * L1-точка включается только при W-покрытии (в слепке на момент T есть факт,
 * релевантный истине), C-точки — контроль молчания памяти, без требования покрытия.
 * Спан-поля (спека 33 §3): trigger/truth — verbatim-вырезки из лога; truth анкеруется
 * репликой truthMessageId (null: C-точки, LME, legacy-точки до спеки 33).
 */
public record EvalPoint(
        String id,
        String level,             // "L1" — предсказание; "C" — контроль (память должна молчать)
        String sourceSession,     // позиция T: сессия живого лога
        long sourceMessageId,     // позиция T: id реплики-развилки M
        String trigger,           // запрос юзера в позиции M (verbatim-спан реплики M)
        String truth,             // что юзер реально сделал дальше (verbatim-спан реплики truthMessageId)
        List<String> must,        // ключевые слова/факты, обязательные в ответе
        List<String> mustNot,     // маркеры, которых в ответе быть не должно
        String pattern,           // паттерн §2.9: какое знание о юзере/проекте тестируем; для C — null
        String coverage,          // W-обоснование: факт слепка, покрывающий истину; для C — null
        String forkType,          // категория развилки (спека 33 §6): словарь fork_types в gestalt_eval; null — legacy/LME
        Long truthMessageId) {    // анкер истины: id реплики-носителя truth; null — C/LME/legacy (спека 33 §3)
}
