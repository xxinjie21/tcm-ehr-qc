#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
真机 E2E（P2-10）：登录 → 主链路 → 关键页断言。

配方（2026-10-05 实测确定；原文《审查问题执行计划》§11 P2-10 —— 该文档已于 2026-10-06 并入 docs/多批次实施计划.md §六）：
  1) 先 `agent-browser install`（一次性，装自带 Chrome；本机已完成）
  2) `open` **会长时间不返回** ⇒ 按「发后不理」处理（用短超时并忽略）
  3) `snapshot -i -c -d 3 --json` 取无障碍树，**必须先断言 origin 是目标页**
     （否则会把「空白页的成功快照」当成通过）
  4) 交互用 `click @eN` / `fill @eN <值>`，ref 从快照 JSON 里按 name 匹配

用法：python tools/e2e.py [--base http://localhost:3000] [--keep]
退出码：0 = 全部通过；1 = 有断言失败；2 = 环境不可用（CLI/dev server）
"""
import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AB = r"D:\ZISHIKU\AI\tcm-ehr-qc\nodejs-global\agent-browser.cmd"
SESSION = "qc-e2e"

results = []


def log(ok, title, detail=""):
    results.append((ok, title, detail))
    print(("  [OK] " if ok else "  [!!] ") + title + (("  —— " + detail) if detail else ""))


def run(args, timeout=90, check=False):
    """调用 CLI；超时/失败都不抛（调用方按语义处理）。"""
    try:
        p = subprocess.run([AB] + args, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return None, "<timeout>"


def snapshot():
    """取快照并解析 JSON；返回 (origin, refs, raw)。"""
    code, out = run(["--session", SESSION, "snapshot", "-i", "-c", "-d", "3", "--json"], timeout=20)
    m = re.search(r'\{[\s\S]*\}', out or "")
    if not m:
        return None, {}, out
    try:
        data = json.loads(m.group(0))
    except ValueError:
        return None, {}, out
    d = data.get("data") or {}
    return d.get("origin"), (d.get("refs") or {}), out


def find_ref(refs, *needles, role=None):
    """按 name 关键字找 ref。role 给定时只在该角色内找（否则会被同名标题抢走）。"""
    for needle in needles:
        for ref, info in refs.items():
            if role and (info.get("role") or "") != role:
                continue
            if needle in (info.get("name") or ""):
                return ref
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:3000")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="123456")
    ap.add_argument("--keep", action="store_true", help="结束时保留浏览器会话（便于人工查看）")
    args = ap.parse_args()

    if not os.path.exists(AB):
        log(False, "agent-browser 存在", AB)
        return 2
    log(True, "agent-browser 存在")

    # dev server 可用性
    try:
        import urllib.request
        with urllib.request.urlopen(args.base + "/login", timeout=5) as r:
            log(r.status == 200, "dev server 可访问", f"{args.base}/login → {r.status}")
    except Exception as e:
        log(False, "dev server 可访问", str(e)[:80])
        return 2

    # 1) 打开登录页（发后不理）
    run(["--session", SESSION, "open", args.base + "/login"], timeout=8)
    origin, refs, raw = snapshot()
    log(origin is not None and origin.startswith(args.base), "快照 origin 是目标页", str(origin))
    if origin is None:
        print("    （快照原始输出片段：" + (raw or "")[:200] + "）")
        return 1

    # 2) 登录
    u_ref = find_ref(refs, "用户名", "username")
    p_ref = find_ref(refs, "密码", "password")
    b_ref = find_ref(refs, "登", role="button")
    log(bool(u_ref and p_ref), "找到用户名/密码输入框", f"user={u_ref} pwd={p_ref}")
    if not (u_ref and p_ref and b_ref):
        log(False, "找到登录按钮", f"btn={b_ref}")
        return 1
    run(["--session", SESSION, "fill", u_ref, args.user])
    run(["--session", SESSION, "fill", p_ref, args.password])
    run(["--session", SESSION, "click", b_ref])
    import time
    time.sleep(3)

    # 3) 断言离开登录页
    origin2, refs2, raw2 = snapshot()
    logged_in = bool(origin2) and "/login" not in origin2
    log(logged_in, "登录后离开 /login", str(origin2))

    # 4) 关键页冒烟（只断言 origin 与有交互元素）
    # 真实路由取自 frontend/src/router/index.js（2026-10-05 核对；此前脚本误写 "/qc"，实际是 "/qc-check"）
    for path, label in (("/dashboard", "首页看板"), ("/review", "人工复核"), ("/records", "病历数据"),
                        ("/nlp-extract", "结构化解析"), ("/qc-check", "质控校验"), ("/governance", "清洗与导出"),
                        ("/dictionary", "术语词典"), ("/dictionary/import", "术语批量导入"),
                        ("/audit-log", "日志审计"), ("/standardization-report", "标准化质量报告"),
                        ("/my-org", "我的组织"), ("/orgs", "组织管理")):
        run(["--session", SESSION, "open", args.base + path], timeout=8)
        # 这些页进页后要拉数据；快照太早会拿到 0 个交互元素 ⇒ 等一拍，仍为空再重试一次
        time.sleep(2)
        o, r, _ = snapshot()
        if not r:
            time.sleep(2)
            o, r, _ = snapshot()
        ok = bool(o) and path.strip("/") in (o or "") and len(r) > 0
        log(ok, f"{label} 页可渲染且有交互元素", f"{o} refs={len(r)}")

    # 5) 汇总
    failed = [t for ok, t, _ in results if not ok]
    print("\n  === E2E 汇总：%d 项通过 / %d 项失败 ===" % (len(results) - len(failed), len(failed)))
    if failed:
        print("  失败项：" + " · ".join(failed))
    if not args.keep:
        run(["--session", SESSION, "close"], timeout=30)
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
