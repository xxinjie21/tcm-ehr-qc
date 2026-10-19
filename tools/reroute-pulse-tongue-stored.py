# -*- coding: utf-8 -*-
"""一次性数据订正：把存量 structured_data 里误落进 symptoms 的舌脉要素归位。

口径与 python-nlp/main.py 的 _reroute_pulse_tongue() / _is_pulse_tongue()
逐字一致（三处同源，勿在此处另立口径）：
  - content 以「脉」开头，或属脉诊三部（尺/寸/关）与脉象组合词 → pulseList
  - content 以「舌」开头，或属舌诊专名（尖/根/苔） → tongueList
  - 按 content 去重：目标数组里已有同一 content 的不再追加
  - 非舌脉要素的症状保持原样、原顺序
  - 排除前缀 关节 / 尺肤 / 苔藓 / 尖锐：首字撞上专名但都是症状域真词

为什么带前缀之外还要判三部/舌诊专名：模型没有脉象/舌象标签，这两类要素会
落进 symptoms，其中一批是**书写时省略了「脉」「舌」前缀**的（尺沉、尖红、
关缓）。2026-10-15 实测这批无前缀的共 3057 条 / 2384 条记录，只判前缀会漏。
判据不限于两字 —— 3~7 字的描述性短语（根部厚腻、寸后及关前反中取明显）反而居多。
「关节/尺肤/苔藓/尖锐」四个前缀是实测踩出来的坑：关节痛等 90+ 个写法、
尺肤热等体征、苔藓样皮损皮肤病、尖锐声声症，都不是舌脉。

为什么单独做：抽取侧修复（_reroute_pulse_tongue）只对**新抽取**生效，
存量已落库的 JSON 不会自动变。第一轮（只判前缀）订正过 3978 条 / 3389 条记录；
本轮补无前缀的那部分（3057 条 / 2384 条记录）。

安全约束：
  - 每条记录先读后写，只动 structured_data 一列 + update_time；
  - 只 UPDATE 真正发生变化的记录（symptoms 元素条数变了才算变化），
    其余一律不碰 —— 不会刷新整表 35352 行的 update_time；
  - records.manually_edited 是 STORED 生成列，不读不写；
  - 运行前必须建备份表（本脚本不代建）。
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

# ---- 舌脉要素判据（与 python-nlp/main.py 同口径）----
PULSE_HEADS = ("尺", "寸", "关")          # 脉诊三部，症状域不会用
TONGUE_HEADS = ("尖", "根", "苔")         # 舌尖 / 舌根 / 舌苔
PT_HEADS = PULSE_HEADS + TONGUE_HEADS
PT_WHITELIST = ("动惕", "动剔", "弦滑", "浮滑", "浮数")
# 首字撞上专名但语义不是舌脉的前缀（都是症状域真词，实测都真实存在）：
#   关节 —— 关节痛/关节怕冷 等 90+ 个写法；尺肤 —— 尺部皮肤，诊断体征非脉象；
#   苔藓 —— 皮肤病；尖锐 —— 声音/湿疣等义；脉下 —— 脉位下方的部位描述（左脉下有一囊肿）。
PT_BLACKLIST = ("关节", "尺肤", "苔藓", "尖锐", "脉下")
# 方位 / 数量前缀：脉位常写「两关偏浮」「右寸后关前」「双寸关」「左关偏浮」，
# 首字不是三部，只判首字会漏。只剥一个字符，避免误收「两手…」「左侧…」类症状。
PT_QUALIFIERS = ("左", "右", "双", "两")


def _pt_core(content: str) -> str:
    """剥掉一个可选的方位/数量前缀（左/右/双/两）。"""
    return content[1:] if content[:1] in PT_QUALIFIERS else content


def is_pulse_tongue(content: str) -> bool:
    """该实体是不是脉象/舌象要素（不管带不带「脉」「舌」前缀，允许一个方位前缀）。

    省略前缀的舌脉要素**不限两字**：实测存量 3~7 字的描述性短语占多数
    （根部厚腻 129、尺后偏沉 62、寸后及关前反中取明显 6），只判两字会漏。
    """
    if not content:
        return False
    core = _pt_core(content)
    # 黑名单在原字与剥前缀后都判：'左关节痛' 剥掉「左」后要能看到「关节」
    if content.startswith(PT_BLACKLIST) or core.startswith(PT_BLACKLIST):
        return False
    if core.startswith(("脉", "舌")):
        return True
    if core.startswith(PT_HEADS):
        # 抽取噪声（如 "尺>"、"寸及"、"关尺>寸"）：含非汉字的一律不当舌脉
        return all("\u4e00" <= ch <= "\u9fff" for ch in content)
    return content in PT_WHITELIST


def is_tongue(content: str) -> bool:
    return _pt_core(content).startswith(("舌", "尖", "根", "苔"))

conn = pymysql.connect(host="localhost", port=3306, user="root", password="123456",
                       database="tcm_ehr", charset="utf8mb4")
cur = conn.cursor()


def move_list(entities, keep_fn, target, content_of):
    """按 keep_fn 分出「该移走」的实体，按 content 去重移入 target。

    返回 (kept, moved, deduped)。deduped 是「目标数组已有同一 content」
    而被判为重复的实体列表 —— 这些**同样要从源数组删掉**（目标侧已有同一要素，
    再留在 symptoms 里就是重复登记），只是不再追加。
    一开始漏了这条分支，导致「规则侧已抽到、模型侧又放了一份」的记录
    判为无变化，实测 3389 条里只覆盖到 555 条。
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


def reroute(data):
    """对单条 structured_data 做舌脉归位。

    返回 (新 dict, 是否有变化, 统计)。统计含
    {@code moved_pulse}/{@code moved_tongue}（真正追加进目标数组的 content）
    与 {@code dedup_pulse}/{@code dedup_tongue}（目标已有、只从 symptoms 删的条数）。
    """
    syms = data.get("symptoms")
    if not isinstance(syms, list):
        return data, False, {}

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
        syms, lambda c: is_pulse_tongue(c) and not is_tongue(c), pulses, content_of)
    kept, moved_tongue, dedup_tongue = move_list(
        kept, lambda c: is_pulse_tongue(c) and is_tongue(c), tongues, content_of)

    if moved_pulse:
        data["pulseList"] = pulses + moved_pulse
    if moved_tongue:
        data["tongueList"] = tongues + moved_tongue
    stats = {
        "moved_pulse": [content_of(e) for e in moved_pulse],
        "moved_tongue": [content_of(e) for e in moved_tongue],
        "dedup_pulse": [content_of(e) for e in dedup_pulse],
        "dedup_tongue": [content_of(e) for e in dedup_tongue],
    }
    changed = len(kept) != len(syms)
    if changed:
        data["symptoms"] = kept
    return data, changed, stats


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
    removed_total = dedup_total = 0
    moved_total = 0
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
            data, changed, stats = reroute(data)
            if not changed:
                continue
            moved_p = stats["moved_pulse"]
            moved_t = stats["moved_tongue"]
            dedup_p = stats["dedup_pulse"]
            dedup_t = stats["dedup_tongue"]
            removed_total += len(moved_p) + len(moved_t) + len(dedup_p) + len(dedup_t)
            dedup_total += len(dedup_p) + len(dedup_t)
            moved_total += len(moved_p) + len(moved_t)
            per_kind.update(moved_p + moved_t + dedup_p + dedup_t)
            changed_rows += 1
            if args.apply:
                cur.execute(
                    "UPDATE records SET structured_data = %s, update_time = NOW() WHERE id = %s",
                    (json.dumps(data, ensure_ascii=False), rid))
        offset += BATCH
        print("  已扫描 %d / %d …" % (min(offset, total), total), flush=True)

    print()
    print("扫描带 structured_data 的记录：%d" % scanned)
    print("发生舌脉归位的记录：%d" % changed_rows)
    print("从 symptoms 移走的要素总数：%d 条（其中 %d 条目标数组已有同一 content、只删不追加）"
          % (removed_total, dedup_total))
    print("真正追加进脉/舌数组的：%d 条" % moved_total)
    if per_kind:
        print("TOP15 被移走的要素：")
        for w, n in per_kind.most_common(15):
            print("   %-12s %d" % (w, n))
    if not args.apply:
        print("（dry-run：未写库）")
    else:
        conn.commit()
        print("已提交写入。")


if __name__ == "__main__":
    main()
