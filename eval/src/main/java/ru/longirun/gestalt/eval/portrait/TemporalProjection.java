package ru.longirun.gestalt.eval.portrait;

import ru.longirun.gestalt.eval.store.FactRepository.StoredFact;

import java.util.ArrayList;
import java.util.List;

/**
 * Временная проекция фактов на границу B — слепок для точки, добавленной в датасет
 * после завершённого инжеста (курсор уже за границей, состояние «как было» из БД
 * не восстановить наивно: там факты всего среза).
 *
 * <p>Мутации фактов строго монотонны (reinforce дописывает evidence и не трогает
 * statement/created_at, конфликты дедупа живут отдельными строками), поэтому:
 * факт существует на момент B ⇔ min(evidence) ≤ B; reinforcement на момент B
 * приближается числом evidence-id ≤ B (не выше текущего счётчика — разложение
 * evidence на события рождения/подкрепления для мульти-id строк безвозвратно
 * потеряно, порядок внутри слепка приближён).
 *
 * <p>Арбитраж на эталонных слепках project-v3 (40 шт, построены циклом): состав
 * фактов совпадает, утечек будущего нет. Факты с пустым evidence исключаются:
 * время рождения непознаваемо, а утечка недопустима.
 */
public final class TemporalProjection {

    private TemporalProjection() {
    }

    public static List<StoredFact> atBound(List<StoredFact> facts, long bound) {
        List<StoredFact> projected = new ArrayList<>();
        for (StoredFact fact : facts) {
            List<Long> evidence = fact.evidenceMessageIds();
            if (evidence == null || evidence.isEmpty()) {
                continue;
            }
            List<Long> prefix = new ArrayList<>();
            for (Long id : evidence) {
                if (id != null && id <= bound) {
                    prefix.add(id);
                }
            }
            if (prefix.isEmpty()) {
                continue;
            }
            int reinforcementAtBound = Math.min(prefix.size(), fact.reinforcementCount());
            projected.add(new StoredFact(
                    fact.id(), fact.ownerId(), fact.sessionId(), fact.projectId(), fact.domain(),
                    fact.scope(), fact.kind(), fact.subjectNorm(), fact.predicateNorm(),
                    fact.objectValue(), fact.statement(), fact.conditions(),
                    reinforcementAtBound, fact.w(), fact.createdAt(), prefix));
        }
        return projected;
    }
}
