# -*- coding: utf-8 -*-
"""一次性数据订正：把存量 `tongueList` 里误落的皮肤病名搬回 `symptoms`。

根因：`python-nlp/main.py` 的 `RULE_TONGUE` 原正则只要求段首是「舌」或「苔」，
皮肤病名以「苔藓」打头（苔藓 / 苔藓样皮疹 / 苔藓样皮损）与舌苔同首字，
于是整段被规则侧收进了 `tongueList`。2026-10-15 实测存量 9 条：

    苔藓样皮疹 ×4 · 苔藓样皮损 ×2 · 苔藓样 ×2 · 苔藓样大减 ×1

抽取侧已修（`RULE_TONGUE` 加负向先行 `(?!藓)`），只对**新抽取**生效；
存量已落库的 JSON 不会自动变，故有本脚本。

**方向与 `reroute-pulse-tongue-stored.py` 相反**：那个是舌脉要素 → 脉/舌数组，
这个是皮肤病名 → 症状数组。别把两者混起来。

搬移口径（与 `_is_pulse_tongue` 的「苔藓」前缀排除同源）：
  - `content` 以「苔藓」开头 ⇒ 皮肤病名，从 `tongueList` 搬到 `symptoms`
  - 「股藓」「扁平苔藓」等不以此打头的**不在纠正范围**——它们本来就在
    `symptoms` 里（位置正确），无需搬动
  - 按 content 去重：`symptoms` 里已有同一 content 的不重复追加
  - 非皮肤病名的舌象保持原样、原顺序

安全约束：
  - 每条记录先读后写，只动 structured_data 一列 + update_time；
  - 只 UPDATE 真正发生变化的记录（tongueList 元素条数变了才算变化），
    其余一律不碰 —— 不刷新整表 update_time；
  - records.manually_edited 是 STORED 生成列，不读不写；
  - 运行前不代建备份表，需要的话自行 `CREATE TABLE ... LIKE records`
    （注意 manually_edited 是生成列，备份须列显式清单）。
用法：
    python tools/fix-tongue-skin-terms-stored.py --dry-run     # 只报数不写
    python tools/fix-tongue-skin-terms-stored.py --apply       # 真写
"""
import argparse
import json

import pymysql

BATCH = 200

# 皮肤病名前缀（与 main.py 的 RULE_TONGUE 负向先行、(?!藓) 同源：苔藓类皮肤专名）
SKIN_PREFIX = ("苔藓",)


def is_skin_term(content: str) -> bool:
    """该实体是不是以「苔藓」打头的皮肤病名。"""
    return bool(content) and content.startswith(SKIN_PREFIX)


def move_list(entities, keep_fn, target, content_of):
    """按 keep_fn 分出「该移走」的实体，按 content 去重移入 target。

    返回 (kept, moved, deduped)。deduped 是「目标数组已有同一 content」而被判为
    重复的实体列表 —— 这些同样要从源数组删掉（目标侧已有同一要素，再留在
    tongueList 里就是重复登记），只是不再追加。
    """
    kept, moved, deduped = [], [], []
    seen = {content_of(x) for x in target}
    for e in entities:
        c = content_of(e)
        if not keep_fn(c):
            kept.append(e)
        elif c in seen:
            deduped.append(e)
        else:
            moved.append(e)
            seen.add(c)
    return kept, moved, deduped


def fix(data):
    """对单条 structured_data 做皮肤病名归位。

    返回 (新 dict, 是否有变化, 统计)。
    """
    tongues = data.get("tongueList")
    if not isinstance(tongues, list):
        return data, False, {}

    def content_of(e):
        if not isinstance(e, dict):
            return ""
        c = e.get("content") or ""
        if not c:
            c = e.get("sourceText") or ""
        return c.strip()

    symptoms = data.get("symptoms")
    symptoms = symptoms if isinstance(symptoms, list) else []

    kept, moved, deduped = move_list(
        tongues, is_skin_term, symptoms, content_of)

    if moved:
        data["symptoms"] = symptoms + moved
    stats = {
        "moved": [content_of(e) for e in moved],
        "deduped": [content_of(e) for e in deduped],
    }
    changed = len(kept) != len(tongues)
    if changed:
        data["tongueList"] = kept
    return data, changed, stats


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry-run", action="store_true")
    g.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456",
                           database="tcm_ehr", charset="utf8mb4")
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM records")
    total = cur.fetchone()[0]
    print("records 总数：%d" % total)

    offset = 0
    scanned = changed_rows = 0
    moved_total = dedup_total = 0
    per_word = {}
    while True:
        cur.execute(
            "SELECT id, structured_data FROM records ORDER BY id LIMIT %s OFFSET %s",
            (BATCH, offset))
        rows = cur.fetchall()
        if not rows:
            break
        for rid, raw in rows:
            if not raw:
                continue
            scanned += 1
            try:
                data = json.loads(raw)
            except Exception as e:
                print("  !! JSON 解析失败 id=%s: %s" % (rid, e))
                continue
            if not isinstance(data, dict):
                continue
            data, changed, stats = fix(data)
            if not changed:
                continue
            changed_rows += 1
            moved_total += len(stats["moved"])
            dedup_total += len(stats["deduped"])
            for w in stats["moved"] + stats["deduped"]:
                per_word[w] = per_word.get(w, 0) + 1
            if args.apply:
                cur.execute(
                    "UPDATE records SET structured_data = %s, update_time = NOW() WHERE id = %s",
                    (json.dumps(data, ensure_ascii=False), rid))
        offset += BATCH
        print("  已扫描 %d / %d …" % (min(offset, total), total), flush=True)

    print()
    print("扫描带 structured_data 的记录：%d" % scanned)
    print("发生皮肤病名归位的记录：%d" % changed_rows)
    print("搬回 symptoms 的皮肤病名：%d 条（其中 %d 条症状里已有、只从舌象删）"
          % (moved_total, dedup_total))
    if per_word:
        print("被搬走的词：")
        for w, n in sorted(per_word.items(), key=lambda x: -x[1]):
            print("   %-12s %d" % (w, n))
    if not args.apply:
        print("（dry-run：未写库）")
    else:
        conn.commit()
        print("已提交写入。")


if __name__ == "__main__":
    main()
