"""P3.0 检索召回评估：对比「整词 ILIKE」「bigram + ILIKE」「pg_trgm 相似度」三种策略。

用法（需 docker 里的 stockcat-postgres 在跑）：

    python scripts/p3_eval/run_eval.py

它把语料灌进**临时表**（事务结束即消失，不碰业务表），对每条查询跑三种检索，
输出 recall@5 与无结果查询的误报情况。结论写进 docs/PLAN_P3_EVAL.md。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from corpus import DOCS, EMPTY_QUERIES, QUERIES  # noqa: E402

DB_CONTAINER = "stockcat-postgres"
DB_USER = "stockcat"
DB_NAME = "stockcat"
TOP_K = 5

# bigram 检索里整词命中应比碎片命中更有价值：整词权重 3，bigram 权重 1
WHOLE_TERM_WEIGHT = 3

# 整词停用的 bigram：虚词切出来的碎片（"怎么""么样"）会把无关段落顶上来。
# 只收"独立成词就没信息量"的双字词，不做单字过滤——单字过滤会破坏"没有""是否"这类实义搭配。
STOP_GRAMS = {
    "怎么", "么样", "什么", "如何", "为什", "到底", "是否", "可以", "我们",
    "认为", "但是", "而且", "如果", "因为", "所以", "没有", "已经", "还是",
    "这个", "那个", "一个", "以及", "对于", "关于",
}


def bigrams(text: str, drop_stop_grams: bool = False) -> list[str]:
    """把查询切成 bigram。单字查询退化为自身，两字查询保留整词。"""
    clean = re.sub(r"\s+", "", text)
    if len(clean) <= 2:
        return [clean] if clean else []
    grams = [clean[i : i + 2] for i in range(len(clean) - 1)]
    if drop_stop_grams:
        kept = [g for g in grams if g not in STOP_GRAMS]
        # 全被过滤掉时退回原切分，宁可带噪声也不能把查询弄没
        grams = kept or grams
    return grams


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def build_sql() -> str:
    lines: list[str] = ["BEGIN;", "CREATE EXTENSION IF NOT EXISTS pg_trgm;"]
    lines.append(
        "CREATE TEMP TABLE chunks (doc_idx int, seq int, title text, content text) ON COMMIT DROP;"
    )

    values: list[str] = []
    for doc_idx, doc in enumerate(DOCS):
        for seq, para in enumerate(doc["paras"]):
            values.append(
                f"({doc_idx}, {seq}, {sql_quote(doc['title'])}, {sql_quote(para)})"
            )
    lines.append("INSERT INTO chunks VALUES\n" + ",\n".join(values) + ";")

    for qid, (query, _) in enumerate(QUERIES, start=1):
        # A：整词匹配（现状：全库 ILIKE '%kw%'）
        # 只比 content：标题命中会让整篇所有段落一起进结果，那不是召回能力，是噪声
        like = sql_quote(f"%{query}%")
        lines.append(
            f"SELECT {qid} AS q, 'A' AS strategy, doc_idx, seq, 0 AS score FROM chunks\n"
            f"WHERE content ILIKE {like}\n"
            f"ORDER BY doc_idx, seq LIMIT {TOP_K};"
        )

        # B：bigram 拆词后 OR 匹配，按「整词命中*3 + bigram 命中数」排序
        grams = bigrams(query)
        gram_array = "ARRAY[" + ",".join(sql_quote(g) for g in grams) + "]"
        or_terms = " OR ".join(
            f"content ILIKE {sql_quote('%' + g + '%')}" for g in grams
        )
        score_expr = (
            f"((CASE WHEN content ILIKE {sql_quote('%' + query + '%')}"
            f" THEN {WHOLE_TERM_WEIGHT} ELSE 0 END)"
            f" + (SELECT count(*) FROM unnest({gram_array}) g WHERE content ILIKE '%'||g||'%'))"
        )
        lines.append(
            f"SELECT {qid} AS q, 'B' AS strategy, doc_idx, seq, {score_expr} AS score FROM chunks\n"
            f"WHERE {or_terms}\n"
            f"ORDER BY score DESC, doc_idx, seq LIMIT {TOP_K};"
        )

        # E：bigram + 最少命中数阈值（宁缺毋滥：命中碎片不足的片段不进结果，不拿噪声凑满 5 条）
        # 阈值按"命中的 bigram 个数"算，不把整词权重算进去，语义才准确
        min_hits = max(1, (len(grams) + 1) // 2)
        gram_hits = f"(SELECT count(*) FROM unnest({gram_array}) g WHERE content ILIKE '%'||g||'%')"
        lines.append(
            f"SELECT q, strategy, doc_idx, seq, score FROM (\n"
            f"  SELECT {qid} AS q, 'E' AS strategy, doc_idx, seq,\n"
            f"         {gram_hits} AS gram_hits, {score_expr} AS score\n"
            f"  FROM chunks WHERE {or_terms}\n"
            f") t WHERE t.gram_hits >= {min_hits}\n"
            f"ORDER BY score DESC, doc_idx, seq LIMIT {TOP_K};"
        )

        # C：pg_trgm 相似度（阈值放低：中文长文本与短查询的 trigram 交集天然很小）
        lines.append(
            f"SELECT {qid} AS q, 'C' AS strategy, doc_idx, seq,"
            f" round(similarity(content, {sql_quote(query)})::numeric, 4) AS score FROM chunks\n"
            f"WHERE similarity(content, {sql_quote(query)}) > 0.01\n"
            f"ORDER BY score DESC, doc_idx, seq LIMIT {TOP_K};"
        )

        # D：bigram + 停用词过滤（虚词碎片不参与召回）；查询里没有虚词时与 B 等价
        grams_d = bigrams(query, drop_stop_grams=True)
        gram_array_d = "ARRAY[" + ",".join(sql_quote(g) for g in grams_d) + "]"
        or_terms_d = " OR ".join(
            f"content ILIKE {sql_quote('%' + g + '%')}" for g in grams_d
        )
        score_d = (
            f"((CASE WHEN content ILIKE {sql_quote('%' + query + '%')}"
            f" THEN {WHOLE_TERM_WEIGHT} ELSE 0 END)"
            f" + (SELECT count(*) FROM unnest({gram_array_d}) g"
            f" WHERE content ILIKE '%'||g||'%'))"
        )
        lines.append(
            f"SELECT {qid} AS q, 'D' AS strategy, doc_idx, seq, {score_d} AS score "
            f"FROM chunks\nWHERE {or_terms_d}\n"
            f"ORDER BY score DESC, doc_idx, seq LIMIT {TOP_K};"
        )

    lines.append("ROLLBACK;")
    return "\n".join(lines)


def run_sql(sql: str) -> str:
    proc = subprocess.run(
        [
            "docker", "exec", "-i", DB_CONTAINER,
            "psql", "-U", DB_USER, "-d", DB_NAME,
            "-A", "-F", "|", "-t", "-v", "ON_ERROR_STOP=1",
        ],
        input=sql.encode("utf-8"),
        capture_output=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            "psql 执行失败：" + proc.stderr.decode("utf-8", errors="replace")
        )
    return proc.stdout.decode("utf-8", errors="replace")


def parse(output: str) -> dict[tuple[int, str], list[tuple[int, int, float]]]:
    """q + strategy -> [(doc_idx, seq, score)]，保持 SQL 返回顺序。"""
    result: dict[tuple[int, str], list[tuple[int, int, float]]] = {}
    for line in output.splitlines():
        line = line.strip()
        if not line or line == "SET" or line.startswith("CREATE"):
            continue
        parts = line.split("|")
        if len(parts) != 5:
            continue
        q, strategy, doc_idx, seq, score = parts
        try:
            key = (int(q), strategy)
            result.setdefault(key, []).append(
                (int(doc_idx), int(seq), float(score or 0))
            )
        except ValueError:
            continue
    return result


def main() -> None:
    sql = build_sql()
    output = run_sql(sql)
    hits = parse(output)

    strategies = {"A": "整词 ILIKE", "B": "bigram", "C": "pg_trgm",
                  "D": "bigram+停用词", "E": "bigram+阈值"}
    totals = {k: {"recall": 0.0, "precision": 0.0, "count": 0,
                  "empty_ok": 0, "empty_total": 0}
              for k in strategies}

    print(f"\n语料：{len(DOCS)} 篇 / {sum(len(d['paras']) for d in DOCS)} 个片段"
          f"　查询：{len(QUERIES)} 条（其中 {len(EMPTY_QUERIES)} 条应无结果）\n")
    header = f"{'查询':<22}" + "".join(f"{s:<18}" for s in strategies.values())
    print(header)
    print("-" * len(header))

    for qid, (query, relevant) in enumerate(QUERIES, start=1):
        relevant_set = set(relevant)
        row = f"{query:<22}"
        for key in strategies:
            got = hits.get((qid, key), [])
            got_set = {(d, s) for d, s, _ in got}
            if relevant_set:
                score = len(relevant_set & got_set) / len(relevant_set)
                precision = len(relevant_set & got_set) / len(got_set) if got_set else 0.0
                totals[key]["recall"] += score
                totals[key]["precision"] += precision
                totals[key]["count"] += 1
                mark = f"{score:.2f}" if got_set else "0.00(空)"
            else:
                totals[key]["empty_total"] += 1
                if not got_set:
                    totals[key]["empty_ok"] += 1
                    mark = "空√"
                else:
                    mark = f"误报{len(got_set)}"
            row += f"{mark:<18}"
        print(row)

    print("\n汇总")
    print("-" * 58)
    for key, name in strategies.items():
        t = totals[key]
        avg = t["recall"] / t["count"] if t["count"] else 0.0
        avg_p = t["precision"] / t["count"] if t["count"] else 0.0
        empty_rate = t["empty_ok"] / t["empty_total"] if t["empty_total"] else 0.0
        print(f"{name:<18} recall@5 = {avg:.3f}　precision@5 = {avg_p:.3f}　"
              f"空查询正确率 = {empty_rate:.0%}（{t['empty_ok']}/{t['empty_total']}）")


if __name__ == "__main__":
    main()
