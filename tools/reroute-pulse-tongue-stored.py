# -*- coding: utf-8 -*-
"""一次性数据订正：把存量 structured_data 里误落进 symptoms 的舌脉要素归位。

口径与 python-nlp/main.py 的 _reroute_pulse_tongue() 逐字一致：
  - symptoms 里 content 以「脉」开头 → 移入 pulseList
  - content 以「舌」开头 → 移入 tongueList
  - 按 content 去重：目标数组里已有同一 content 的不再追加
  - 非脉/舌开头的症状保持原样、原顺序

为什么单独做：抽取侧修复（_reroute_pulse_tongue）只对**新抽取**生效，
存量已落库的 JSON 不会自动变。实测存量 3978 条（脉 1790 + 舌 2188），
涉及 3389 条记录；已归一的脉/舌开头症状为 0 条，说明新链路已干净，
这 3978 条全靠本脚本。

安全约束：
  - 每条记录先读后写，只动 structured_data 一列 + update_time；
  - 只 UPDATE 真正发生变化的记录（变迁计数器比对前后 JSON），
    其余一律不碰 —— 不会刷新整表 35352 行的 update_time；
  - records.manually_edited 是 STORED 生成列，不读不写；
  - 运行前必须建备份表（本脚本不代建，见 README/执行命令）。
用法：
    python tools/reroute-pulse-tongue-stored.py --dry-run     # 只报数不写
    python tools/reroute-pulse-tongue-stored.py --apply       # 真写
"""
import argparse
import io
import json
import sys
from collections import Counter

import pymysql

BATCH = 200

conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456",
                       database="tcm_ehr", charset="utf8mb4")
cur = conn.cursor()


def move_list(entities, keep_fn, target, content_of):
    """按 keep_fn 分出「该移走」的实体，按 content 去重移入 target。

    返回 (kept, moved, deduped)。deduped 是「目标数组已有同一 content」
    而被判为重复的条数 —— 这些**同样要从源数组删掉**（目标侧已有同一要素，
    再留在 symptoms 里就是重复登记），只是不再追加。
    一开始漏了这条分支，导致「规则侧已抽到、模型侧又放了一份」的记录
    判为无变化，实测 3389 条里只覆盖到 555 条。
    """
    kept, moved, deduped = [], [], 0
    seen = {content_of(x) for x in target}
    for e in entities:
        c = content_of(e)
        if not keep_fn(c):
            kept.append(e)
        elif c in seen:
            deduped += 1
        else:
            moved.append(e)
            seen.add(c)
    return kept, moved, deduped


def reroute(data):
    """对单条 structured_data 做舌脉归位。返回 (新 dict, 是否有变化)。"""
    syms = data.get("symptoms")
    if not isinstance(syms, list):
        return data, False
    changed = False

    def content_of(e):
        if not isinstance(e, dict):
            return ""
        c = e.get("content") or ""
        if not c:
            c = e.get("sourceText") or ""
        return c.strip()

    pulses = data.get("pulseList")
    tongues = data.get("tongueList")
    pulses = pulses if isinstance(pulses, list) else []
    tongues = tongues if isinstance(tongues, list) else []

    kept, moved_pulse, dedup_pulse = move_list(
        syms, lambda c: c.startswith("脉"), pulses, content_of)
    kept, moved_tongue, dedup_tongue = move_list(
        kept, lambda c: c.startswith("舌"), tongues, content_of)

    if moved_pulse:
        data["pulseList"] = pulses + moved_pulse
    if moved_tongue:
        data["tongueList"] = tongues + moved_tongue
    if len(kept) != len(syms):
        data["symptoms"] = kept
        changed = True
    return data, changed


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry-run", action="store_true")
    g.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    cur.execute("SELECT COUNT(*) FROM records")
    total = cur.fetchone()[0]
    print("records 总数：%d" % total)

    offset = 0
    scanned = changed_rows = 0
    moved_pulse = moved_tongue = 0
    per_kind = Counter()
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
            before = json.dumps(data, sort_keys=True, ensure_ascii=False)
            # 记录移动前的脉/舌要素总数，事后用差值得出本次移走多少条
            pre_pulse = sum(1 for e in (data.get("pulseList") or [])
                            if isinstance(e, dict) and (e.get("content") or e.get("sourceText") or "").strip().startswith("脉"))
            pre_tongue = sum(1 for e in (data.get("tongueList") or [])
                             if isinstance(e, dict) and (e.get("content") or e.get("sourceText") or "").strip().startswith("舌"))
            data, changed = reroute(data)
            if not changed:
                continue
            after = json.dumps(data, ensure_ascii=False)
            post_pulse = sum(1 for e in data.get("pulseList", [])
                             if isinstance(e, dict) and (e.get("content") or e.get("sourceText") or "").strip().startswith("脉"))
            post_tongue = sum(1 for e in data.get("tongueList", [])
                              if isinstance(e, dict) and (e.get("content") or e.get("sourceText") or "").strip().startswith("舌"))
            moved_pulse += post_pulse - pre_pulse
            moved_tongue += post_tongue - pre_tongue
            changed_rows += 1
            if args.apply:
                cur.execute(
                    "UPDATE records SET structured_data = %s, update_time = NOW() WHERE id = %s",
                    (after, rid))
        offset += BATCH
        print("  已扫描 %d / %d …" % (min(offset, total), total), flush=True)

    print()
    print("扫描带 structured_data 的记录：%d" % scanned)
    print("发生舌脉归位的记录：%d" % changed_rows)
    print("移入 pulseList 的脉象要素：%d 条" % moved_pulse)
    print("移入 tongueList 的舌象要素：%d 条" % moved_tongue)
    print("（其中目标数组已有同一 content、只从 symptoms 删不追加的情形已计入"
          "「发生舌脉归位的记录」，不计入上面两个移入数）")
    if not args.apply:
        print("（dry-run：未写库）")
    else:
        conn.commit()
        print("已提交写入。")


if __name__ == "__main__":
    main()
