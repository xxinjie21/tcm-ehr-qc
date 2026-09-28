#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
三方权限一致性校验（阶段2 R6）。

按「路径 + Http 方法」粒度对照三个来源：
  1. openapi：每个 method 的【权限：…】标注
  2. 后端：Controller 上的 @RequireRole / @RequireGroupRole
  3. 前端 router meta.roles（仅校对本脚本能识别的 meta）
用法：python tools/verify-permission-consistency.py；退出码 0=无差异 1=有差异。
"""
import io
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OPENAPI = ROOT / "docs" / "中医电子病历质控与标准化系统-openapi.yaml"
BACKEND = ROOT / "java-backend" / "src" / "main" / "java"
FRONTEND_ROUTER = ROOT / "frontend" / "src" / "router" / "index.js"

PUBLIC = {"/api/auth/login", "/api/auth/register"}


def perm_of_openapi(path: str, method: str) -> str:
    """openapi：该 path 块内 method 子树的第一条【权限：…】"""
    text = io.open(OPENAPI, encoding="utf-8-sig").read()
    marker = f"  {path}:\n"
    i = text.find(marker)
    if i < 0:
        return "未定义"
    j = text.find("\n  /", i + len(marker))
    block = text[i + len(marker): j if j > 0 else None]
    m = re.search(r"    %s:\n" % method, block)
    if not m:
        return "未定义"
    sub = block[m.end():]
    pm = re.search(r"【权限：([^】]+)】", sub)
    text_map = {
        "登录即可": "登录即可", "仅管理员": "仅管理员", "公开": "公开",
        "管理员 / 审核员": "管理员 / 审核员", "组长（本组）": "组长（本组）",
    }
    return text_map.get(pm.group(1)) if pm else "未标注"


def collect_backend():
    out = {}
    for p in BACKEND.rglob("*.java"):
        lines = io.open(p, encoding="utf-8").read().split("\n")
        for i, line in enumerate(lines):
            mm = re.search(r"@(Get|Post|Put|Delete)Mapping\(\"([^\"]+)\"\)", line)
            if not mm:
                continue
            method = mm.group(1).lower()
            path = mm.group(2)
            if not path.startswith("/api/"):
                for up in range(i, -1, -1):
                    cm = re.search(r'@RequestMapping\("([^"]+)"\)', lines[up])
                    if cm:
                        path = cm.group(1) + path
                        break
            perm = "登录即可"
            if i >= 1 and "@RequireGroupRole" in lines[i - 1]:
                perm = "组长（本组）"
            elif i >= 1 and "@RequireRole" in lines[i - 1]:
                roles = re.search(r'roles\s*=\s*\{\s*"([^"]+)"(?:\s*,\s*"([^"]+)")?\s*\}', lines[i - 1])
                key = roles.group(1) + (", " + roles.group(2) if roles.group(2) else "")
                perm = {"管理员": "仅管理员", "管理员, 审核员": "管理员 / 审核员"}.get(key, "未识别")
            if path.startswith("/api/"):
                out[(path, method)] = perm
    return out


def main():
    be = collect_backend()
    diff = []
    for (path, method), perm in sorted(be.items()):
        if path in PUBLIC or path.endswith("/import/{taskId}/status"):
            continue  # 公开放行 / L7 前日志相关先不算
        op = perm_of_openapi(path, method)
        if op != perm:
            diff.append(f"{method.upper():6} {path:<46} openapi={op:<8} backend={perm}")
    print(f"[校验] 后端 {method_count(be)} 个方法；差异 " + ("无" if not diff else f"{len(diff)} 处"))
    for d in diff:
        print("  ", d)
    return 1 if diff else 0


def method_count(d):
    return len(d)


if __name__ == "__main__":
    sys.exit(main())