"""诊断：把某几条查询的真实返回逐条打出来，看排序与收窄规则到底做了什么。

不属于评估流程，是排查工具。用法：

    python scripts/p3_eval/diagnose.py 渠道库存 止损纪律

脚本会导入 50 篇语料、跑指定的查询、打印每条命中的标题/段号/分数/命中词/是否仅标题命中，
结束后删除导入的资料。
"""
from __future__ import annotations

import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from run_eval_live import call, import_docs, library_total  # noqa: E402


def main(queries: list[str]) -> None:
    if library_total():
        print("资料库非空，先清空再诊断")
        return
    details, ids = import_docs()
    by_id = {d["id"]: d for d in details.values()}
    try:
        for q in queries:
            print(f"\n=== 查询：{q}")
            result = call("GET", "/library/search?" + urllib.parse.urlencode(
                {"keyword": q, "limit": 10}))
            print("拆词:", result["terms"])
            for i, h in enumerate(result["hits"], 1):
                detail = by_id[h["documentId"]]
                chunk = next(c for c in detail["chunks"] if c["id"] == h["chunkId"])
                print(f"  {i}. score={h['score']:<3} 标题命中={h['titleMatched']!s:<5} "
                      f"词={h['matchedTerms']}  《{h['documentTitle']}》第{h['seq']}段")
                print(f"     {chunk['content'][:70]}")
    finally:
        for i in ids:
            call("DELETE", f"/library/documents/{i}?confirm=true")
        print(f"\n清理后剩余：{library_total()} 篇")


if __name__ == "__main__":
    main(sys.argv[1:] or ["渠道库存"])
