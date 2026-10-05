#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次 12 · 12d 验收脚本：未归一分类的 SQL 版是否自洽、是否仍与接口一致。

为什么需要它：把 unmatch/coverage/normalizableRate 从「Java 逐条解析」换成「库内 JSON_TABLE」
之后，原先那个 mock 掉 mapper 的单测就失去意义（被 mock 的正是要被删掉的 Java 分类）。
本脚本改从**真实库**出发校验，比 mock 更接近实际：

  ① 分项自洽：total = physicalSign + misrouted + fragment + dictionaryGap
  ② TOP 明细自洽：每一条都确实属于「词表缺口」那一类
     （长度 > 2 · 不以 脉/舌 开头 · 不含 压痛/触痛/叩痛/反跳痛）
  ③ TOP 明细量级自洽：TOP 各条次数之和 <= dictionaryGap
  ④ 可选：若后端可用且已加载新代码（unmatched.top 非空），逐项与接口值比对

用法：python tools/verify-unmatched-sql.py [--org grp-default-2026] [--api]
退出码：0 = 全部通过；1 = 有不一致
"""
import argparse
import io
import os
import subprocess
import sys

SIGN = ["压痛", "触痛", "叩痛", "反跳痛"]
FIXTURE_ORG = "bench-fixture"
CLASS_EXPR = "COALESCE(NULLIF(jt.c, ''), jt.s)"

BREAKDOWN = """
SELECT
  COUNT(*) AS total,
  COALESCE(SUM(CASE WHEN {e} REGEXP '压痛|触痛|叩痛|反跳痛' THEN 1 ELSE 0 END), 0) AS physicalSign,
  COALESCE(SUM(CASE WHEN NOT ({e} REGEXP '压痛|触痛|叩痛|反跳痛') AND ({e} LIKE '脉%' OR {e} LIKE '舌%') THEN 1 ELSE 0 END), 0) AS misrouted,
  COALESCE(SUM(CASE WHEN NOT ({e} REGEXP '压痛|触痛|叩痛|反跳痛') AND NOT ({e} LIKE '脉%' OR {e} LIKE '舌%') AND CHAR_LENGTH({e}) <= 2 THEN 1 ELSE 0 END), 0) AS fragment,
  COALESCE(SUM(CASE WHEN NOT ({e} REGEXP '压痛|触痛|叩痛|反跳痛') AND NOT ({e} LIKE '脉%' OR {e} LIKE '舌%') AND CHAR_LENGTH({e}) > 2 THEN 1 ELSE 0 END), 0) AS dictionaryGap
FROM records r, JSON_TABLE(r.structured_data, '$.symptoms[*]'
  COLUMNS (c VARCHAR(200) PATH '$.content', s VARCHAR(200) PATH '$.sourceText', normLevel VARCHAR(20) PATH '$.normLevel')) jt
WHERE r.org_id = '{org}' AND jt.normLevel IS NULL;
"""

TOP = """
SELECT {e} AS content, COUNT(*) AS n
FROM records r, JSON_TABLE(r.structured_data, '$.symptoms[*]'
  COLUMNS (c VARCHAR(200) PATH '$.content', s VARCHAR(200) PATH '$.sourceText', normLevel VARCHAR(20) PATH '$.normLevel')) jt
WHERE r.org_id = '{org}' AND jt.normLevel IS NULL
  AND NOT ({e} REGEXP '压痛|触痛|叩痛|反跳痛')
  AND NOT ({e} LIKE '脉%' OR {e} LIKE '舌%')
  AND CHAR_LENGTH({e}) > 2
GROUP BY content ORDER BY n DESC LIMIT 15;
"""


def mysql(sql):
    p = subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD=123456", "windows-mysql",
                        "mysql", "-uroot", "--default-character-set=utf8mb4", "-B", "tcm_ehr"],
                       input=sql.encode("utf-8"), capture_output=True)
    out = p.stdout.decode("utf-8", "replace")
    rows = [ln.split("\t") for ln in out.strip().splitlines() if ln.strip()]
    if not rows:
        # INSERT/DELETE 没有结果集：返回空而不是抛 IndexError（夹具段就踩过这个坑）
        return [], []
    return rows[0], rows[1:]


def num_rows(sql):
    head, rows = mysql(sql)
    return {} if not rows else {k: int(v) for k, v in zip(head, rows[0])}


def fixture_check():
    """规则夹具：用与生产同一条 SQL 断言分类规则（替代被删除的三个 Java 用例）。"""
    mysql("DELETE FROM records WHERE org_id='%s';" % FIXTURE_ORG)
    mysql("INSERT INTO records (id, org_id, structured_data, score, grade) VALUES "
          "('fx-1','%s','{\"symptoms\":["
          "{\"content\":\"双\",\"sourceText\":\"双\"},"
          "{\"content\":\"脉细数\",\"sourceText\":\"脉细数\"},"
          "{\"content\":\"腹部压痛\",\"sourceText\":\"腹部压痛\"},"
          "{\"content\":\"神疲乏力\",\"sourceText\":\"神疲乏力\"},"
          "{\"content\":\"发热\",\"sourceText\":\"发热\",\"normLevel\":1}]}',95,'合格');" % FIXTURE_ORG)
    got = num_rows(BREAKDOWN.format(e=CLASS_EXPR, org=FIXTURE_ORG))
    exp = {"total": 4, "physicalSign": 1, "misrouted": 1, "fragment": 1, "dictionaryGap": 1}
    ok = got == exp
    print("  [OK] ⑤ 规则夹具：双→碎片 · 脉细数→错放 · 腹部压痛→体征 · 神疲乏力→缺口 · 发热已归一不计"
          if ok else "  [FAIL] ⑤ 规则夹具不符：期望 %s 实际 %s" % (exp, got))
    _, rows = mysql(TOP.format(e=CLASS_EXPR, org=FIXTURE_ORG))
    top = {r[0]: int(r[1]) for r in rows}
    if top == {"神疲乏力": 1}:
        print("  [OK] ⑥ 夹具 TOP：只有「神疲乏力」入榜（体征/错放/碎片都被排除）")
    else:
        print("  [FAIL] ⑥ 夹具 TOP 不符：%s" % top)
        ok = False
    mysql("DELETE FROM records WHERE org_id='%s';" % FIXTURE_ORG)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--org", default="grp-default-2026")
    ap.add_argument("--api", action="store_true", help="额外与后端接口比对")
    args = ap.parse_args()
    e = CLASS_EXPR
    ok = True

    head, rows = mysql(BREAKDOWN.format(e=e, org=args.org))
    if not rows:
        print("  无数据（org=%s）" % args.org)
        return 0
    b = dict(zip(head, rows[0]))
    nums = {k: int(v) for k, v in b.items()}
    print("  分项：total=%(total)s physicalSign=%(physicalSign)s misrouted=%(misrouted)s "
          "fragment=%(fragment)s dictionaryGap=%(dictionaryGap)s" % nums)

    s = nums["physicalSign"] + nums["misrouted"] + nums["fragment"] + nums["dictionaryGap"]
    if s != nums["total"]:
        print("  [FAIL] 分项之和 %d != total %d" % (s, nums["total"]))
        ok = False
    else:
        print("  [OK] ① 分项自洽：四项之和 = total = %d" % s)

    head2, rows2 = mysql(TOP.format(e=e, org=args.org))
    bad = 0
    for r in rows2:
        c = r[0]
        if len(c) <= 2 or c.startswith("脉") or c.startswith("舌") or any(x in c for x in SIGN):
            print("  [FAIL] TOP 里混入了不属于词表缺口的项：%r" % c)
            bad += 1
    if bad:
        ok = False
    else:
        print("  [OK] ② TOP 明细 %d 条全部属于词表缺口类" % len(rows2))

    tot = sum(int(r[1]) for r in rows2)
    if tot > nums["dictionaryGap"]:
        print("  [FAIL] TOP 之和 %d > dictionaryGap %d" % (tot, nums["dictionaryGap"]))
        ok = False
    else:
        print("  [OK] ③ TOP 之和 %d <= dictionaryGap %d" % (tot, nums["dictionaryGap"]))
    if rows2:
        print("      TOP3：" + "、".join("%s(%s)" % (r[0], r[1]) for r in rows2[:3]))

    ok = fixture_check() and ok

    if args.api:
        try:
            import json
            import urllib.request
            req = urllib.request.Request("http://localhost:8080/api/auth/login",
                                         data=json.dumps({"username": "admin", "password": "123456"}).encode(),
                                         headers={"Content-Type": "application/json"})
            tok = json.loads(urllib.request.urlopen(req, timeout=15).read())["data"]["token"]
            req2 = urllib.request.Request("http://localhost:8080/api/stats/standardization-report",
                                          headers={"Authorization": "Bearer " + tok})
            d = json.loads(urllib.request.urlopen(req2, timeout=180).read())["data"]["unmatched"]
            if not d.get("top"):
                print("  [SKIP] 接口 unmatched.top 为空 —— 后端可能仍是重启前的旧代码，无法比对")
            else:
                same = all(int(d[k]) == nums[k] for k in
                           ("total", "physicalSign", "misrouted", "fragment", "dictionaryGap"))
                print(("  [OK] ④ 五项与接口一致" if same else "  [FAIL] ④ 与接口不一致：接口=%s" % d))
                ok = ok and same
        except Exception as ex:
            print("  [SKIP] 接口比对失败：%s" % str(ex)[:80])

    print("  ==== 结论：%s ====" % ("通过 ✓" if ok else "有不一致 ✗"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
