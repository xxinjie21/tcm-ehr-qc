#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 11：性能基线数据生成器（可复现、可精确清理）。

设计要点：
  1. 只写自己的组织 `bench-org` ⇒ 清理就是一条 DELETE，绝不碰真实数据。
  2. `text_hash` 留 NULL：它参与 UNIQUE(org_id, text_hash)，NULL 在唯一索引里不冲突。
  3. structured_data 按真实九类实体生成（含未归一项），让统计/报告/归一路径真的被压到。
  4. **列名清单驱动**：列数与值数由 assert 保证一致 —— 手写 `%` 占位符数错一次就白跑一次。
  5. 分批发 INSERT（每批 1000 行），避免单条巨型 INSERT 的假性能。

用法：
  python tools/gen-bench-data.py --rows 35000 --exec     # 生成并灌库
  python tools/gen-bench-data.py --clean                 # 删除 bench-org 全部数据
"""
import argparse
import io
import json
import os
import random
import subprocess
import sys

ORG = "bench-org"
DEPTS = ["针灸科", "推拿科", "中医内科", "骨伤科", "康复科"]
GRADES = ["合格", "待复核", "无效"]
SYMPTOMS = ["神疲乏力", "腰膝酸软", "失眠", "心悸健忘", "胃脘胀痛", "嗳气", "鼻痒", "喷嚏",
            "颈前肿物", "心慌手抖", "流清涕反复发作", "头晕", "口干", "畏寒肢冷"]
PULSES = ["脉细数", "脉弦劲有力", "脉浮", "左尺无力"]
TONGUES = ["舌淡红", "舌暗", "舌红少苔", "苔薄白"]
DISEASES = ["慢性胃炎", "慢性肾病", "甲亢", "高血压", "糖尿病", "膝关节骨性关节炎", "过敏性鼻炎"]
PATTERNS = ["肝肾亏虚证", "脾胃虚弱证", "气滞血瘀证", "阴虚火旺证"]
HERBS = ["黄芪", "当归", "白芍", "熟地黄", "川芎", "茯苓", "白术", "甘草"]
FORMULAS = ["六味地黄丸", "补中益气汤", "血府逐瘀汤", "逍遥散"]
TREATMENTS = ["中药内服", "针灸", "推拿", "中药外敷"]
CAUSES = ["外感风寒", "饮食不节", "情志失调"]  # 病因类无词表 ⇒ 造出真实的未归一

COLS = ["id", "registration_no", "outpatient_no", "gender", "age", "visit_count",
        "western_diagnosis", "tcm_diagnosis", "present_illness", "chief_complaint", "self_report",
        "inspection", "pulse", "tongue", "physical_exam", "pattern", "prescription", "follow_up",
        "treatment_effect", "department", "doctor_id", "visit_time", "structured_data", "qc_results",
        "score", "grade", "org_id", "status", "governed", "create_time", "update_time"]


class Raw(str):
    """原样写入 SQL 的值（如 NOW()）。"""


def st(rnd):
    def ent(c, ok=True):
        e = {"content": c, "sourceText": c}
        if ok:
            e["normLevel"] = "exact"
        return e
    return {
        "chiefComplaint": [ent("失眠、心悸健忘8月")],
        "presentIllness": [ent("反复发作，劳累后加重")],
        "symptoms": [ent(s, rnd.random() < 0.75) for s in rnd.sample(SYMPTOMS, rnd.randint(1, 4))],
        "pulse": [ent(rnd.choice(PULSES), rnd.random() < 0.6)],
        "tongue": [ent(rnd.choice(TONGUES), rnd.random() < 0.8)],
        "disease": [ent(rnd.choice(DISEASES))],
        "pattern": [ent(rnd.choice(PATTERNS), rnd.random() < 0.7)],
        "herb": [ent(h) for h in rnd.sample(HERBS, rnd.randint(2, 5))],
        "formula": [ent(rnd.choice(FORMULAS), rnd.random() < 0.7)],
        "treatment": [ent(rnd.choice(TREATMENTS))],
        "cause": [ent(rnd.choice(CAUSES), False)],
    }


def values_for(i, rnd):
    """返回与 COLS 一一对应的值列表（长度由 assert 守住）。"""
    grade = GRADES[2] if i % 7 == 0 else (GRADES[1] if i % 3 == 0 else GRADES[0])
    vt = Raw("'2026-%02d-%02d %02d:%02d:00'" % ((i % 12) + 1, (i % 27) + 1, 8 + (i % 10), i % 60))
    j = json.dumps(st(rnd), ensure_ascii=False)
    return ["bench-%06d" % i, "BN%06d" % i, "M%06d" % i, "男" if i % 2 else "女",
            "%d" % (20 + i % 60), Raw(str((i % 5) + 1)),
            "", "慢性胃炎", "反复发作", "失眠、心悸健忘8月", "自诉内容", "望诊内容",
            json.dumps([{"content": rnd.choice(PULSES)}], ensure_ascii=False),
            json.dumps([{"content": rnd.choice(TONGUES)}], ensure_ascii=False),
            "查体内容", rnd.choice(PATTERNS),
            json.dumps([{"content": h} for h in HERBS[:3]], ensure_ascii=False),
            "随访内容", "好转", DEPTS[i % len(DEPTS)], "D%03d" % (i % 40), vt,
            j, "[]", Raw(str(60 + (i % 41))), grade, ORG, "completed", Raw("0"),
            Raw("NOW()"), Raw("NOW()")]


def sql_literal(v):
    if isinstance(v, Raw):
        return str(v)
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def gen(rows, path):
    rnd = random.Random(20261005)  # 固定种子 ⇒ 同一份数据可复现
    head = "INSERT INTO records (" + ",".join(COLS) + ") VALUES\n"
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("SET NAMES utf8mb4;\n")
        batch = []
        for i in range(rows):
            vals = values_for(i, rnd)
            assert len(vals) == len(COLS), "列数 %d != 值数 %d（列名清单是唯一事实来源）" % (len(COLS), len(vals))
            batch.append("(" + ",".join(sql_literal(v) for v in vals) + ")")
            if len(batch) >= 1000:
                f.write(head + ",\n".join(batch) + ";\n")
                batch = []
        if batch:
            f.write(head + ",\n".join(batch) + ";\n")
    return path


def mysql_stdin(path):
    with io.open(path, "r", encoding="utf-8") as f:
        p = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=123456", "windows-mysql",
                            "mysql", "-uroot", "--default-character-set=utf8mb4", "-B", "tcm_ehr"],
                           stdin=f, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()


def mysql_exec(sql):
    p = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=123456", "windows-mysql",
                        "mysql", "-uroot", "--default-character-set=utf8mb4", "-B", "tcm_ehr", "-e", sql],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rows", type=int, default=35000)
    ap.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "."), "bench.sql"))
    ap.add_argument("--exec", action="store_true")
    ap.add_argument("--clean", action="store_true")
    args = ap.parse_args()

    if args.clean:
        code, out = mysql_exec("DELETE FROM records WHERE org_id='%s';" % ORG)
        print("  clean rc=%s %s" % (code, out[:200]))
        return code

    gen(args.rows, args.out)
    print("  已生成 %s（%d 行，%.1f MB）" % (args.out, args.rows, os.path.getsize(args.out) / 1048576.0))
    if args.exec:
        code, out = mysql_stdin(args.out)
        print("  灌库 rc=%s %s" % (code, out[:300]))
        return code
    return 0


if __name__ == "__main__":
    sys.exit(main())
