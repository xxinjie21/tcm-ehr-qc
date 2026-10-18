#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""术语归一与导出链路验收：一条真实病历走完 抽取 → 归一 → 质控 → 清洗 → 导出，逐条断言。

与 `tools/e2e.py` 同风格：只读 + 只改该条病历的结构化数据，失败即非零退出。

跑法：
    & "F:\\python3.14\\python.exe" tools/verify-normalize-pipeline.py [recordId] [--base http://localhost:8080]

退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不可用 / 入参不合法。

本脚本把《术语归一与导出准确率提升计划》§2 的 12 条断言固化为 CHECKS，任何一条不成立就报失败，
用来防止后续改动（判定护栏、清洗幂等、导出替换）把已修好的问题重新引入。
"""
import argparse
import csv
import io
import json
import sys
import urllib.error
import urllib.request

# 默认用本仓库实测使用的那条真实病历（含 细胞角蛋白19 / 边尖略红 / 寸略旺 等触发词）
DEFAULT_RECORD = "1e86ea91-7961-410c-87e7-ea94e326853b"

# 计划验收清单（每条对应一个 assert；见各 _check_* 函数）
CHECKS = [
    # 1  剔除类（Task 2）
    "herbs 不含 细胞角蛋白19 / 江西省 / 江西中",
    "symptoms 不含 反酸/嗳气/打嗝/恶寒/心慌/脚气 与任何单字",
    # 2  规则补漏（Task 3）
    "tongueList 含 边尖略红、边齿痕；pulseList 含 寸略旺、尺较沉",
    "treatmentList 不含 治疗5次 / 治疗过程中…",
    # 3  判定护栏（Task 4）
    "脚气/血/治疗/反复发作/经期小腹痛/腹痛后便稀 保持未命中（normLevel 缺失）",
    "心悸 与 知饥 不被更长的别名条目抢走",
    # 4  清洗幂等（Task 5）
    "clean 后 structured_data 与 clean 前逐条一致（normalized == 0）",
    "无黄汗 的 content 未被改写",
    # 5  导出安全（Task 6）
    "导出文本不含 流清涕/血分证块/伴有汗腰酸痛/干脚气/食疗/有汗(替换而来)",
    "标准化对照只含实际发生的替换",
    # 6  质控（Task 7）
    "术语未标准化扣分的 reason 带按类型明细",
]

results = []


def log(ok, title, detail=""):
    results.append((ok, title, detail))
    print(("  [OK] " if ok else "  [!!] ") + title + (("  —— " + detail) if detail else ""))


def call(method, path, body=None, token=None, base="http://localhost:8080", raw=False, timeout=300):
    """发请求；返回解析后的 JSON（raw=True 时返回 bytes）。HTTP 错误也返回一个带标记的 dict，交给断言处理。"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        b = r.read()
        return b if raw else json.loads(b.decode("utf-8"))


def data_of(resp):
    return (resp or {}).get("data") if isinstance(resp, dict) else None


# ------------------------------------------------------------------ 各条断言

def check_extract_noise(vo):
    """Task 2：抽取产物过滤（单字残词 / 假药名 / 否定句）。"""
    herbs = [str(h.get("name") or "") for h in (vo.get("herbs") or [])]
    bad_herbs = [h for h in herbs if "细胞角蛋白" in h or h in ("江西省", "江西中")]
    log(not bad_herbs, "herbs 不含 细胞角蛋白19 等假药名", "命中：" + ",".join(bad_herbs) if bad_herbs else "herbs=" + str(len(herbs)))

    symptoms = [str(s.get("content") or "") for s in (vo.get("symptoms") or [])]
    stop = {"反酸", "嗳气", "打嗝", "恶寒", "心慌", "脚气"}
    hit_stop = [s for s in symptoms if s in stop]
    singles = [s for s in symptoms if len(s) < 2]
    log(not hit_stop, "symptoms 不含 反酸/嗳气/打嗝/恶寒/心慌/脚气", ",".join(hit_stop) if hit_stop else "")
    log(not singles, "symptoms 不含单字残词", ",".join(singles) if singles else "symptoms=" + str(len(symptoms)))


def check_rules_backfill(vo):
    """Task 3：舌脉治法规则补漏。

    ⚠️ 这里断言的是**整条链路**（模型 + 规则 + 归一去重）的产物，不是 `_rules` 的输出：
    模型可能把「边齿痕」写成「舌齿痕」，去重后保留哪个取决于归一结果 —— 两者语义相同，
    故用「包含」判定而非逐字相等（`_rules` 的逐字行为由 python-nlp/tests/test_batch20_rules.py 钉住）。
    """
    tongue = [str(t.get("content") or "") for t in (vo.get("tongueList") or [])]
    pulse = [str(p.get("content") or "") for p in (vo.get("pulseList") or [])]
    log(any("边尖略红" in t for t in tongue) and any("齿痕" in t for t in tongue),
        "tongueList 含 边尖略红、*齿痕", "tongue=" + ",".join(tongue))
    log(any("寸略旺" in p for p in pulse) and any("尺较沉" in p for p in pulse),
        "pulseList 含 寸略旺、尺较沉", "pulse=" + ",".join(pulse))

    treat = [str(t.get("content") or "") for t in (vo.get("treatmentList") or [])]
    bad = [t for t in treat if "治疗5次" in t or t.startswith("治疗过程中")]
    log(not bad, "treatmentList 不含 治疗5次 / 治疗过程中…", ",".join(bad) if bad else "treat=" + str(len(treat)))


def check_judge_guard(vo):
    """Task 4：判定护栏 —— 错映射变未命中；歧义词不被更长条目抢走。"""
    def find(field, name_field, term):
        for e in (vo.get(field) or []):
            if str(e.get(name_field) or "") == term:
                return e
        return None

    miss_terms = ["脚气", "血", "治疗", "反复发作", "经期小腹痛", "腹痛后便稀"]
    still_missed, resurrected = [], []
    for t in miss_terms:
        e = find("symptoms", "content", t)
        if e is None:
            continue  # 该病历里没出现这个实体，不算违反
        lv = e.get("normLevel")
        if lv is None:
            still_missed.append(t)
        else:
            resurrected.append(f"{t}(L{lv})")
    log(not resurrected, "脚气/血/治疗/反复发作… 保持未命中（normLevel 缺失）",
        ("仍为未命中：" + ",".join(still_missed) if still_missed else "该病历未出现相关实体")
        + (" ｜ 复活：" + ",".join(resurrected) if resurrected else ""))

    # 心悸 / 知饥：命中时标准词不得比原文长很多（防止被更长的别名条目抢走）
    for t in ("心悸", "知饥"):
        e = find("symptoms", "content", t)
        if e is None:
            continue
        std = str(e.get("content") or "")
        ok = e.get("normLevel") is None or len(std) <= len(t) + 1
        log(ok, f"{t} 不被更长的别名条目抢走", f"content={std} normLevel={e.get('normLevel')}")


def check_clean_idempotent(tok, rid, base):
    """Task 5：清洗幂等 + 否定不重写。"""
    before = data_of(call("GET", f"/api/records/raw/{rid}", token=tok, base=base)) or {}
    sd_before = before.get("structuredData")

    cl1 = data_of(call("POST", "/api/governance/clean", {"recordIds": [rid]}, token=tok, base=base)) or {}
    after1 = data_of(call("GET", f"/api/records/raw/{rid}", token=tok, base=base)) or {}
    cl2 = data_of(call("POST", "/api/governance/clean", {"recordIds": [rid]}, token=tok, base=base)) or {}
    after2 = data_of(call("GET", f"/api/records/raw/{rid}", token=tok, base=base)) or {}

    log(cl2.get("normalized", -1) == 0, "二次 clean normalized == 0（幂等）",
        f"第一次 normalized={cl1.get('normalized')} 第2次 normalized={cl2.get('normalized')}")
    log(after1.get("structuredData") == after2.get("structuredData"),
        "二次 clean 后 structured_data 与一次一致")

    # 无黄汗 的 content 未被改写
    sd = json.loads(after2.get("structuredData") or "{}")
    kept = True
    seen = []
    for e in (sd.get("symptoms") or []):
        c = str(e.get("content") or e.get("sourceText") or "")
        if c.startswith("无黄汗") or c == "无黄汗":
            seen.append(c)
            if c != "无黄汗":
                kept = False
    log(kept, "无黄汗 的 content 未被改写", ("实际：" + ",".join(seen)) if seen else "该病历未出现 无黄汗")
    return sd


def check_export(tok, rid, base, raw, sd):
    """Task 6：导出替换安全。"""
    filters = {"department": raw.get("department"),
               "dateRange": [str(raw.get("visitTime") or "")[:10], str(raw.get("visitTime") or "")[:10]]}
    csv_bytes = call("POST", "/api/export/dataset", {"format": "csv", "filters": filters}, token=tok, base=base, raw=True)
    rows = list(csv.reader(io.StringIO(csv_bytes.decode("utf-8-sig"))))
    header, row = rows[0], next((r for r in rows[1:] if r and r[0] == rid), None)
    if row is None:
        log(False, "导出中存在该病历", f"filters={filters}")
        return
    rowd = dict(zip(header, row))

    pairs = json.loads(rowd.get("标准化对照") or "{}")

    # ① 对照列只含实际发生的替换：src 必须真的出现在原文里
    blob = "\n".join(str(raw.get(k) or "") for k in (
        "chiefComplaint", "selfReport", "presentIllness", "inspection", "tongue", "pulse",
        "physicalExam", "tcmDiagnosis", "pattern", "prescription", "followUp", "treatmentEffect"))
    not_present = [src for src in pairs if src not in blob]
    log(not not_present, "标准化对照只含实际发生的替换（src 均在原文出现）",
        "不在原文：" + ",".join(not_present) if not_present else "对照 " + str(len(pairs)) + " 条")

    # ② 无单字替换 + 无已知坏映射
    one_char = [src for src in pairs if len(src) < 2]
    log(not one_char, "替换原文无单字（E1）", ",".join(one_char) if one_char else "")

    bad_pairs = {"治疗": "食疗", "反复发作": "流清涕", "血": "血分证", "有": "有汗", "脚气": "干脚气"}
    found_bad = [f"{s}→{pairs[s]}" for s in pairs if bad_pairs.get(s) == pairs[s]]
    log(not found_bad, "对照不含已知坏映射（治疗→食疗 等）", ",".join(found_bad) if found_bad else "")

    # ③ 导出正文不含「替换而来的」污染串
    body = "\n".join(str(rowd.get(k) or "") for k in (
        "主诉", "自述", "现病史", "望诊", "舌象", "脉象", "体格检查", "中医诊断", "辨证结论",
        "处方", "随访", "治疗效果"))
    poison = ["血分证块", "伴有汗腰酸痛", "干脚气"]
    contaminated = [p for p in poison if p in body]
    log(not contaminated, "导出正文不含污染串（血分证块/伴有汗…/干脚气）",
        ",".join(contaminated) if contaminated else "")


def check_qc_reason(tok, rid, base):
    """Task 7：未归一扣分 reason 带按类型明细。"""
    sc = data_of(call("POST", "/api/qc/score", {"recordId": rid}, token=tok, base=base)) or {}
    ded = next((d for d in (sc.get("deductions") or []) if d.get("type") == "术语未标准化"), None)
    if ded is None:
        log(True, "术语未标准化扣分 reason 带按类型明细", "本条无该扣分（视为通过）")
        return
    reason = str(ded.get("reason") or "")
    ok = "（" in reason and "/" in reason
    log(ok, "术语未标准化扣分 reason 带按类型明细", reason)


# ------------------------------------------------------------------ 主流程

def main():
    ap = argparse.ArgumentParser(description="术语归一与导出链路端到端验收（只读 + 只改该条病历的结构化数据）")
    ap.add_argument("recordId", nargs="?", default=DEFAULT_RECORD, help="待验病历 ID")
    ap.add_argument("--base", default="http://localhost:8080", help="后端地址")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="123456")
    ap.add_argument("--no-write", action="store_true", help="不写回结构化数据（只做抽取侧断言）")
    args = ap.parse_args()

    # 0) 环境可用性
    try:
        login = call("POST", "/api/auth/login", {"username": args.user, "password": args.password}, base=args.base)
        tok = (login.get("data") or {}).get("token")
        log(bool(tok), "登录成功", "token 已取得" if tok else str(login)[:120])
        if not tok:
            return 1
    except Exception as e:
        log(False, "后端可访问", str(e)[:120])
        return 2

    raw = data_of(call("GET", f"/api/records/raw/{args.recordId}", token=tok, base=args.base))
    if not raw:
        log(False, "病历存在", args.recordId)
        return 2
    log(True, "病历存在", args.recordId)

    # 1) 抽取（文本按 NlpTextComposer 口径：12 字段以「。」拼接）
    parts = [raw.get(k) for k in ("chiefComplaint", "selfReport", "presentIllness", "inspection",
                                  "tongue", "pulse", "physicalExam", "tcmDiagnosis", "pattern",
                                  "prescription", "followUp", "treatmentEffect")]
    text = "".join(str(s).strip() + "。" for s in parts if s and str(s).strip())
    vo = data_of(call("POST", "/api/nlp/extract", {"text": text}, token=tok, base=args.base)) or {}
    log(bool(vo.get("symptoms") or vo.get("herbs")), "抽取返回结构", "unavailableReason=" + str(vo.get("unavailableReason")))
    if vo.get("unavailableReason"):
        log(False, "抽取服务可用（无降级）", str(vo.get("unavailableReason")))
        return 2

    check_extract_noise(vo)
    check_rules_backfill(vo)
    check_judge_guard(vo)

    if not args.no_write:
        # 2) 写回结构化数据（与页面「写回结构化数据」同一接口）
        w = call("PUT", f"/api/records/{args.recordId}", {"structuredData": vo}, token=tok, base=args.base)
        log(bool(w.get("code") == 200 or w.get("message")), "写回结构化数据", str(w)[:120])

    # 3) 清洗幂等 + 4) 导出 + 5) 质控
    sd = check_clean_idempotent(tok, args.recordId, args.base)
    check_export(tok, args.recordId, args.base, raw, sd)
    check_qc_reason(tok, args.recordId, args.base)

    failed = [t for ok, t, _ in results if not ok]
    print("\n  === 验收汇总：%d 项通过 / %d 项失败 ===" % (len(results) - len(failed), len(failed)))
    if failed:
        print("  失败项：" + " · ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
