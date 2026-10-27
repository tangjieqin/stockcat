"""列出语料里某个词真正出现在哪些片段——用于复核标注是否漏标。

标注漏标的后果是把 recall 算低（系统明明找到了，却被判成没找到），
而漏标的典型场景就是高频词查询：语料里十段都在讲"渠道库存"，
标注时只凭印象写了三段。

用法：

    python scripts/p3_eval/audit_labels.py 渠道库存 茅台 批价 止损

脚本导入 50 篇后按切片结果遍历，打印每个词出现的全部片段，跑完自动清理。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from run_eval_live import call, import_docs, library_total  # noqa: E402


def main(words: list[str]) -> None:
    if library_total():
        print("资料库非空，先清空再跑")
        return
    details, ids = import_docs()
    try:
        for word in words:
            print(f"\n=== 「{word}」出现的片段")
            total = 0
            for key, d in details.items():
                for c in d["chunks"]:
                    if word in c["content"]:
                        total += 1
                        idx = c["content"].index(word)
                        print(f"  [{key} seq={c['seq']}] …{c['content'][max(0, idx - 30):idx + 40]}…")
            print(f"  共 {total} 段")
    finally:
        for i in ids:
            call("DELETE", f"/library/documents/{i}?confirm=true")
        print(f"\n清理后剩余：{library_total()} 篇")


if __name__ == "__main__":
    main(sys.argv[1:] or ["渠道库存"])
