"""P3.4 检索质量评估：走**真实后端链路**，而不是用 SQL 模拟。

与 P3.0（`run_eval.py`）的区别在于测的是什么：

| | P3.0 | P3.4（本脚本） |
|---|---|---|
| 数据通路 | 临时表 + 手写 SQL | HTTP 调真实接口：导入 → 切片器 → 检索服务 |
| 切片 | 一段一片 | `LibraryChunkSplitter`（300–600 字合并、标题进 heading） |
| 排序 | 脚本里复刻的 score | 服务端 `LibraryChunkMapper` 的 `lateral` 打分 + 同篇收窄 |
| 引用 | 不测 | 校验 `charStart/charEnd` 能否回原文、`citation` 段号是否等于 `seq` |

所以 P3.0 回答"这个策略方向行不行"，本脚本回答"这套实现能不能用"。

用法（先把验证实例起在 8082，且**资料库必须为空**）：

    python scripts/p3_eval/run_eval_live.py

脚本自己导入 50 篇、跑完 20 条查询、校验引用、最后删掉导入的资料并核对库已归零。
中途失败会尽力清理，不会把 50 篇合成资料留在业务库里。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from corpus50 import DOCS, QUERIES  # noqa: E402

API = "http://localhost:8082/api/v1"
TOP_K = 5


# --------------------------------------------------------------------------- HTTP

def call(method: str, path: str, body: dict | None = None) -> dict:
    url = API + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> {e.code}: {detail}") from e


def library_total() -> int:
    return call("GET", "/library/documents?page=1&size=1")["total"]


# --------------------------------------------------------------------------- 工具

def utf16_slice(text: str, start: int, end: int) -> str:
    """按 Java 的 char 下标（UTF-16 code unit）切 Python 字符串。

    中文都在 BMP 内，直接 `text[start:end]` 结果一样；但语料里一旦出现 emoji
    就会错位，而错位会让"引用可追溯"的校验悄悄失效。所以这里按 UTF-16 编码切，
    让校验口径与 Java 的 `substring` 完全一致。
    """
    buf = text.encode("utf-16-le")
    return buf[start * 2:end * 2].decode("utf-16-le")


def import_docs() -> tuple[dict[str, dict], list[int]]:
    """逐篇导入，返回 key -> detail 与已导入的 id 列表。"""
    details: dict[str, dict] = {}
    ids: list[int] = []
    for i, doc in enumerate(DOCS, start=1):
        is_note = doc["key"].startswith("note-")
        body = {
            "title": doc["title"],
            "sourceType": "PASTE" if is_note else "FILE",
            "sourceRef": None if is_note else doc["key"] + ".md",
            "publisher": doc["publisher"],
            "publishedAt": doc["published_at"],
            "content": "\n\n".join(doc["paras"]),
            "instrumentIds": [],
            "note": None,
        }
        detail = call("POST", "/library/documents", body)
        details[doc["key"]] = detail
        ids.append(detail["id"])
        if i % 10 == 0 or i == len(DOCS):
            print(f"  已导入 {i}/{len(DOCS)} 篇")
    return details, ids


def resolve_anchors(details: dict[str, dict]) -> tuple[dict, list[str]]:
    """把 (doc_key, needle) 标注定位到具体 chunkId。

    切片有 80 字重叠，跨边界的文字会同时出现在相邻两个片段里。这里只取**第一个**命中的片段：
    重复内容不该被算成两个正确答案（否则同一条内容出现两次，recall 分母凭空翻倍，
    而系统其实只找到了一份证据）。

    定位不到才是真问题——说明语料被改动过，或者标注记错了字。必须报出来，
    不能悄悄跳过（跳过会虚高 recall）。
    """
    anchors: dict[tuple[str, str], int] = {}
    problems: list[str] = []
    for _, rel in QUERIES:
        for key, needle in rel:
            detail = details[key]
            matched = [c["id"] for c in detail["chunks"] if needle in c["content"]]
            if matched:
                anchors[(key, needle)] = matched[0]
            else:
                problems.append(f"{key}：标注片段找不到「{needle}」")
    return anchors, problems


def check_traceability(hits: list[dict], details_by_id: dict[int, dict]) -> list[str]:
    """校验每条命中能否按 charStart/charEnd 回原文取到片段本身。"""
    errors: list[str] = []
    for hit in hits:
        detail = details_by_id[hit["documentId"]]
        chunk = next((c for c in detail["chunks"] if c["id"] == hit["chunkId"]), None)
        if chunk is None:
            errors.append(f"chunkId={hit['chunkId']} 不在 documentId={hit['documentId']} 的片段里")
            continue
        if (chunk["charStart"], chunk["charEnd"]) != (hit["charStart"], hit["charEnd"]):
            errors.append(
                f"chunkId={hit['chunkId']} 偏移不一致：检索 {hit['charStart']}-{hit['charEnd']} "
                f"vs 详情 {chunk['charStart']}-{chunk['charEnd']}")
            continue
        sliced = utf16_slice(detail["content"], hit["charStart"], hit["charEnd"])
        if sliced != chunk["content"]:
            errors.append(
                f"chunkId={hit['chunkId']} 按偏移回原文取不到片段本身"
                f"（取到 {len(sliced)} 字，片段 {len(chunk['content'])} 字）")
        if hit["seq"] != chunk["seq"]:
            errors.append(f"chunkId={hit['chunkId']} 段号不一致：检索 {hit['seq']} vs 详情 {chunk['seq']}")
    return errors


def check_citation(hits: list[dict]) -> list[str]:
    """校验工具接口的 citation 与片段的标题/段号一致，且格式固定。"""
    errors: list[str] = []
    for hit in hits:
        expected = f"《{hit['title']}》· 第 {hit['seq']} 段"
        if hit["citation"] != expected:
            errors.append(f"chunkId={hit['chunkId']} citation 不符：{hit['citation']!r} != {expected!r}")
    return errors


# --------------------------------------------------------------------------- 主流程

def main() -> int:
    print(f"后端：{API}")
    health = call("GET", "/system/health")
    print(f"健康检查：{health.get('status', health)}\n")

    existing = library_total()
    if existing:
        print(f"中止：资料库已有 {existing} 篇资料。评估会导入 50 篇合成语料，"
              f"不能与真实资料混在一起。\n请先清空（或在空库上跑）："
              f"\n  curl \"{API}/library/documents?page=1&size=100\"  # 逐篇 DELETE .../documents/{{id}}?confirm=true")
        return 2

    print(f"语料：{len(DOCS)} 篇 / {sum(len(d['paras']) for d in DOCS)} 段 / "
          f"{sum(len(''.join(d['paras'])) for d in DOCS)} 字")
    print(f"查询：{len(QUERIES)} 条（其中 {sum(1 for _, r in QUERIES if not r)} 条应无结果）\n")

    imported_ids: list[int] = []
    details: dict[str, dict] = {}
    exit_code = 0
    try:
        print("导入…")
        details, imported_ids = import_docs()
        details_by_id = {d["id"]: d for d in details.values()}
        chunk_total = sum(d["chunkCount"] for d in details.values())
        sizes = [c["charEnd"] - c["charStart"] for d in details.values() for c in d["chunks"]]
        print(f"  切片：{chunk_total} 个片段，平均 {sum(sizes) // len(sizes)} 字，"
              f"最短 {min(sizes)} / 最长 {max(sizes)}\n")

        anchors, problems = resolve_anchors(details)
        if problems:
            print("标注定位问题（评估集自身的问题，会让 recall 虚高或虚低）：")
            for p in problems:
                print("  -", p)
            print()

        print(f"{'查询':<26}{'recall':<10}{'precision':<12}说明")
        print("-" * 84)

        recall_sum = precision_sum = 0.0
        scored = 0
        empty_ok = empty_total = 0
        all_hits: list[dict] = []
        misses: list[str] = []
        false_positives: list[str] = []

        for query, rel in QUERIES:
            expected = {anchors[(key, needle)] for key, needle in rel if (key, needle) in anchors}
            result = call("GET", "/library/search?" + urllib.parse.urlencode(
                {"keyword": query, "limit": TOP_K}))
            hits = result["hits"]
            all_hits.extend(hits)
            got = {h["chunkId"] for h in hits}

            if expected:
                hit_n = len(expected & got)
                recall = hit_n / len(expected)
                precision = hit_n / len(got) if got else 0.0
                recall_sum += recall
                precision_sum += precision
                scored += 1
                note = f"答案 {len(expected)} 段，命中 {hit_n}"
                missing = expected - got
                if missing:
                    names = []
                    for cid in sorted(missing):
                        for d in details.values():
                            ch = next((c for c in d["chunks"] if c["id"] == cid), None)
                            if ch:
                                names.append(f"《{d['title']}》第{ch['seq']}段")
                                break
                    misses.append(f"{query}：漏 " + "；".join(names))
                extra = got - expected
                if extra:
                    false_positives.append(f"{query}：多返回 {len(extra)} 段")
                print(f"{query:<26}{recall:<10.2f}{precision:<12.2f}{note}")
            elif rel:
                # 标注全部定位失败：这是评估集坏了，不能当成"应无结果"算通过
                print(f"{query:<26}{'?':<10}{'?':<12}标注解析失败，跳过计分")
                exit_code = 1
            else:
                empty_total += 1
                if not got:
                    empty_ok += 1
                    print(f"{query:<26}{'—':<10}{'—':<12}空√（正确返回无结果）")
                else:
                    false_positives.append(f"{query}：应无结果，误报 {len(got)} 段")
                    print(f"{query:<26}{'—':<10}{'—':<12}误报 {len(got)} 段！")

        print("\n汇总")
        print("-" * 84)
        print(f"recall@{TOP_K}     = {recall_sum / scored:.3f}（{scored} 条有答案的查询）")
        print(f"precision@{TOP_K}  = {precision_sum / scored:.3f}")
        if empty_total:
            print(f"空查询正确率       = {empty_ok}/{empty_total}（{empty_ok / empty_total:.0%}）")

        # 引用可追溯：这是"引用正确率 100%"里可机器判定的那一半
        trace_errors = check_traceability(all_hits, details_by_id)
        tool_hits: list[dict] = []
        for query, rel in QUERIES:
            if not rel:
                continue
            resp = call("GET", "/agent/tools/library/search?" + urllib.parse.urlencode(
                {"keyword": query, "limit": TOP_K}))
            tool_hits.extend(resp["data"]["hits"])
        cite_errors = check_citation(tool_hits)

        print(f"\n引用可追溯（{len(all_hits)} 条命中，按 charStart/charEnd 回原文逐条比对）")
        print("-" * 84)
        if trace_errors:
            for e in trace_errors[:10]:
                print("  ✗", e)
            print(f"  失败 {len(trace_errors)} 条")
            exit_code = 1
        else:
            print(f"  全部通过：{len(all_hits)}/{len(all_hits)}，偏移与段号均与详情一致")

        print(f"\n引用格式（工具接口 {len(tool_hits)} 条命中）")
        print("-" * 84)
        if cite_errors:
            for e in cite_errors[:10]:
                print("  ✗", e)
            exit_code = 1
        else:
            print(f"  全部通过：citation 均为《标题》· 第 N 段，N 等于片段真实 seq")

        if misses or false_positives:
            print("\n失败明细")
            print("-" * 84)
            for m in misses:
                print("  漏:", m)
            for f in false_positives:
                print("  多:", f)

        print("\n判定门槛：recall@5 ≥ 0.80 且引用可追溯 100%")
        recall_avg = recall_sum / scored
        ok = recall_avg >= 0.80 and not trace_errors and not cite_errors
        print(f"  结论：{'通过，P3 收口' if ok else '未通过，先优化检索与排序，不上向量'}")

    finally:
        # 无论成败都清理：合成语料不能留在业务库里
        if imported_ids:
            print("\n清理…")
            for doc_id in imported_ids:
                try:
                    call("DELETE", f"/library/documents/{doc_id}?confirm=true")
                except RuntimeError as e:
                    print("  删除失败:", doc_id, e)
            remaining = library_total()
            print(f"  剩余资料：{remaining} 篇" + ("" if remaining == 0 else "  ← 需人工清理"))
            if remaining:
                exit_code = 1
    return exit_code


if __name__ == "__main__":
    started = time.time()
    code = main()
    print(f"\n耗时 {time.time() - started:.1f}s")
    sys.exit(code)
