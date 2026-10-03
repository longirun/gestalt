#!/usr/bin/env python3
"""Диагностический проб (ярус судьи): LLM-судья на точках, проваленных машинным
exact-match оракулом. Не часть каркаса: без fingerprints, без runs-обвязки.
Референс — реальный ответ ассистента из honcho_memory (первое сообщение после
триггера M). Результаты: out/judge_probe_<exp>.md + сырые ответы и кэш в
out/judge_probe_<exp>/ (кэш изолирован по эксперименту — ответы плеч между
экспериментами несравнимы).

Запуск: python3 tools/judge_probe.py [--exp project-v4] [--points id1,id2|--all]
По умолчанию: exp=project-v4, both-fail точки + B-only точки последнего report.
"""
import base64
import json
import math
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

EVAL = Path(__file__).resolve().parents[1]


def props():
    p = {}
    for line in (EVAL / "local.properties").read_text().splitlines():
        m = re.match(r"^([a-z.\-]+)\s*=\s*(.*)$", line)
        if m:
            p[m.group(1)] = m.group(2).strip()
    return p


def psql(url, user, password, sql):
    m = re.match(r"jdbc:postgresql://([^:/]+):(\d+)/(\w+)", url)
    host, port, db = m.group(1), m.group(2), m.group(3)
    import os
    env = dict(os.environ, PGPASSWORD=password)
    r = subprocess.run(
        ["psql", f"host={host} port={port} dbname={db} user={user}",
         "-tA", "-c", sql],
        capture_output=True, text=True, env=env, check=True)
    return r.stdout.strip()


def rows(url, user, password, sql):
    """Каждая строка: 'col1|col2|...' (контент — base64, многострочность исключена)."""
    out = psql(url, user, password, sql)
    return [line.split("|") for line in out.split("\n") if line] if out else []


def b64(s):
    return base64.b64encode(s.encode()).decode() if s else ""


def unb64(s):
    return base64.b64decode(s.encode()).decode() if s else ""


def chat(cfg, system, user):
    base = cfg.get("llm.answer.base-url") or cfg["llm.base-url"]
    model = cfg.get("llm.answer.model") or cfg["llm.model"]
    body = {"model": model, "temperature": 0,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": user}]}
    effort = cfg.get("llm.answer.reasoning-effort") or cfg.get("llm.reasoning-effort", "")
    if effort:
        body["reasoning_effort"] = effort
    key = cfg.get("llm.answer.api-key") or cfg.get("llm.api-key", "")
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        method="POST", data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    return json.loads(urllib.request.urlopen(req, timeout=300).read())["choices"][0]["message"]["content"]


JUDGE_SYSTEM = """You are a strict but fair judge of question-answering quality against a reference.
You will see: QUESTION (user's question), REFERENCE (the historically given answer, ground truth),
MUST TOKENS (identifiers a correct answer is expected to convey), ANSWER A and ANSWER B.
Arm A had access to long-term memory, arm B did not — do not let this bias you.

For each arm and each must token decide:
- "covered": the answer conveys this token's information — literal match, spelling/case/morphology
  variant, or a semantically equivalent reference to the same entity/fact as used in the REFERENCE
  (closely related official names of the same concept count; a different entity does NOT count).
- "absent": the answer does not convey it.
arm "pass" = every must token "covered".

Output STRICT JSON only, no markdown:
{"a": {"tokens": [{"t": "<token>", "s": "covered|absent", "note": "<=12 words"}], "pass": true|false},
 "b": {"tokens": [{"t": "<token>", "s": "covered|absent", "note": "<=12 words"}], "pass": true|false},
 "comment": "<=15 words: the key substantive difference between the answers"}"""


def judge_payload(point, reference, ans_a, ans_b):
    def cut(s, n):
        s = s or ""
        return s if len(s) <= n else s[:n] + " …[truncated]"
    return (f"QUESTION:\n{point['trigger']}\n\n"
            f"REFERENCE (historically given answer):\n{cut(reference, 1800)}\n\n"
            f"MUST TOKENS: {json.dumps(point.get('must') or [])}\n\n"
            f"ANSWER A:\n{cut(ans_a, 1800)}\n\n"
            f"ANSWER B:\n{cut(ans_b, 1800)}\n\n"
            f"Judge both answers. STRICT JSON.")


def mcnemar(b, c):
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def main():
    cfg = props()
    exp = "project-v4"
    if "--exp" in sys.argv:
        exp = sys.argv[sys.argv.index("--exp") + 1]
    tcfg = (cfg["target.db.url"], cfg.get("target.db.user", ""), cfg.get("target.db.password", ""))
    scfg = (cfg["source.db.url"], cfg["source.db.user"], cfg["source.db.password"])

    points = {p["id"]: p for p in
              map(json.loads, (EVAL / cfg.get("dataset.file", "dataset/points.jsonl")).read_text().splitlines())}

    if "--points" in sys.argv:
        targets = sys.argv[sys.argv.index("--points") + 1].split(",")
    else:
        targets = [r[0] for r in rows(*tcfg,
            f"SELECT point_id FROM verdicts WHERE experiment='{exp}' AND NOT a_pass AND NOT b_pass")]
        targets += [r[0] for r in rows(*tcfg,
            f"SELECT point_id FROM verdicts WHERE experiment='{exp}' AND b_pass AND NOT a_pass")]

    machine = {r[0]: (r[1] == "true", r[2] == "true") for r in rows(*tcfg,
        f"SELECT point_id, a_pass::text, b_pass::text FROM verdicts WHERE experiment='{exp}'")}

    answers = {r[0] + "|" + r[1]: unb64(r[2]) for r in rows(*tcfg,
        f"SELECT point_id, arm, replace(encode(convert_to(answer,'UTF8'),'base64'), E'\\n', '') "
        f"FROM answers WHERE experiment='{exp}'")}

    out_dir = EVAL / "out" / f"judge_probe_{exp}"
    out_dir.mkdir(parents=True, exist_ok=True)
    judged = {}
    for pid in targets:
        p = points[pid]
        log = rows(*scfg,
            f"SELECT id::text, peer_name, replace(encode(convert_to(content,'UTF8'),'base64'), E'\\n', '') FROM messages "
            f"WHERE session_name='{p['sourceSession']}' "
            f"AND date_trunc('day', created_at) BETWEEN '{cfg['source.day-from']}'::date "
            f"AND '{cfg.get('source.day-to', cfg['source.day-from'])}'::date ORDER BY id")
        idx = next((i for i, r in enumerate(log) if r[0] == str(p["sourceMessageId"])), None)
        if idx is None:
            print(f"[JUDGE] {pid}: trigger M not found in source log — skipped", file=sys.stderr)
            continue
        reference = next((unb64(r[2]) for r in log[idx + 1:]
                          if r[1] not in ("user", "human")), None)
        if not reference:
            print(f"[JUDGE] {pid}: no reference answer after M — skipped", file=sys.stderr)
            continue
        cached = out_dir / f"{pid}.json"
        if cached.exists() and "--no-cache" not in sys.argv:
            raw = cached.read_text()
        else:
            raw = chat(cfg, JUDGE_SYSTEM,
                       judge_payload(p, reference, answers.get(pid + "|a", ""), answers.get(pid + "|b", "")))
            cached.write_text(raw)
        txt = re.sub(r"^```(json)?|```$", "", raw.strip(), flags=re.M).strip()
        try:
            v = json.loads(txt[txt.index("{"):txt.rindex("}") + 1])
        except Exception as e:
            print(f"[JUDGE] {pid}: unparseable judge output ({e}) — skipped", file=sys.stderr)
            continue
        judged[pid] = v
        print(f"[JUDGE] {pid}: a_pass={v['a']['pass']} b_pass={v['b']['pass']} — {v.get('comment', '')}")

    # агрегат: машинные вердикты перекрыты судейскими
    final = dict(machine)
    for pid, v in judged.items():
        final[pid] = (bool(v["a"]["pass"]), bool(v["b"]["pass"]))

    a_pass = sum(1 for a, b in final.values() if a)
    b_pass = sum(1 for a, b in final.values() if b)
    both_fail = [pid for pid, (a, b) in final.items() if not a and not b]
    only_a = [pid for pid, (a, b) in final.items() if a and not b]
    only_b = [pid for pid, (a, b) in final.items() if b and not a]
    p_val = mcnemar(len(only_a), len(only_b))
    l1 = {pid: v for pid, v in final.items() if points[pid]["level"] == "L1"}
    l1_a = sum(1 for a, b in l1.values() if a)
    l1_b = sum(1 for a, b in l1.values() if b)
    machine_a = sum(1 for a, b in machine.values() if a)
    machine_b = sum(1 for a, b in machine.values() if b)
    machine_lift = round((machine_a - machine_b) * 100 / len(final)) if final else 0
    ml1 = {pid: v for pid, v in machine.items() if points.get(pid, {}).get("level") == "L1"}
    ml1_a = sum(1 for a, b in ml1.values() if a)
    ml1_b = sum(1 for a, b in ml1.values() if b)

    lines = [
        f"# Проб LLM-судьи ({exp}) — точки, проваленные машинным оракулом",
        f"Модель судьи: {cfg.get('llm.answer.model') or cfg.get('llm.model')} · temperature 0 · референс: ответ ассистента после M",
        "",
        f"| метрика | машинный ярус | ярус судьи (перекрытие) |",
        f"|---|---|---|",
        f"| pass A / B (n={len(final)}) | {machine_a} / {machine_b} | {a_pass} / {b_pass} |",
        f"| only A / only B | — | {len(only_a)} / {len(only_b)} |",
        f"| lift | {machine_lift:+d}pp | {round((a_pass - b_pass) * 100 / len(final)) if final else 0:+d}pp |",
        f"| McNemar exact | — | p = {p_val:.3f} |",
        f"| L1-срез (n={len(l1)}): A / B | {ml1_a} / {ml1_b} | {l1_a} / {l1_b} |",
        "",
        "## По точкам (судья):",
    ]
    for pid in targets:
        if pid in judged:
            v = judged[pid]
            ta = "; ".join(f"{t['t']}:{t['s']}" for t in v["a"]["tokens"])
            tb = "; ".join(f"{t['t']}:{t['s']}" for t in v["b"]["tokens"])
            lines.append(f"- `{pid}` — A: {v['a']['pass']} ({ta}) | B: {v['b']['pass']} ({tb}) — {v.get('comment', '')}")
        else:
            lines.append(f"- `{pid}` — не судён (нет референса/парса)")
    lines.append(f"\nОба провалены после судьи: {', '.join(both_fail) or '—'}")
    (EVAL / "out" / f"judge_probe_{exp}.md").write_text("\n".join(lines) + "\n")
    print(f"\n[JUDGE] итог ({exp}): A {a_pass}/{len(final)} vs B {b_pass}/{len(final)} "
          f"(only A {len(only_a)} / only B {len(only_b)}), p={p_val:.3f}; "
          f"L1-срез: A {l1_a}/{len(l1)} vs B {l1_b}/{len(l1)}")
    print(f"[JUDGE] отчёт: out/judge_probe_{exp}.md; сырые ответы: out/judge_probe_{exp}/")


if __name__ == "__main__":
    main()
