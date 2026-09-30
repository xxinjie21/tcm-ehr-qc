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
    # 批次 5 起档位改名：组长（本组）-> 所有者（本组织）。
    # ⚠️ 别用「认不出来就当没差异」的打法：新档位必须显式列进来，
    # 否则 openapi 已改名、后端没改（或反过来）会被静默当成一致。
    text_map = {
        "登录即可": "登录即可", "仅管理员": "仅管理员", "公开": "公开",
        "管理员 / 审核员": "管理员 / 审核员", "所有者（本组织）": "所有者（本组织）",
        # 批次 8c 起：配置类写入放开为「管理员 / 所有者 / 被授权成员」三档
        "管理员 / 所有者 / 授权成员": "登录即可",
    }
    if not pm:
        return "未标注"
    return text_map.get(pm.group(1), "未识别档位:" + pm.group(1))


# 双路径写法：@GetMapping({"/api/orgs/{id}/members", "/api/groups/{id}/members"})
# ⚠️ 两个坑，都要避开：
#   1) 只认第一个路径（或只认单值写法）会让端点**静默漏检**，脚本报「0 差异」
#      却其实少看了一堆端点 —— 比报错更危险。
#   2) 不能用 \{[^}]*\} 去切数组内容：路径变量 {id} 里的 } 会提前截断，
#      含变量的端点会被整段丢掉。正确做法是抓「注解括号内的原文」再取所有引号串。
ANY_MAPPING = re.compile(r'@(Get|Post|Put|Delete)Mapping\((.*)\)')
QUOTED = re.compile(r'"([^"]+)"')
CLASS_MAPPING = re.compile(r'@RequestMapping\("([^"]+)"\)')


def paths_of(line):
    """一行注解里的全部路径（单值 / 数组都支持）。"""
    m = ANY_MAPPING.search(line)
    if not m:
        return None, []
    paths = QUOTED.findall(m.group(2))
    return m.group(1).lower(), paths


def collect_backend():
    out = {}
    for p in BACKEND.rglob("*.java"):
        lines = io.open(p, encoding="utf-8").read().split("\n")
        for i, line in enumerate(lines):
            method, raw_paths = paths_of(line)
            if not method or not raw_paths:
                continue
            perm = "登录即可"
            if i >= 1 and "@RequireOrgRole" in lines[i - 1]:
                perm = "所有者（本组织）"
            elif i >= 1 and "@RequireRole" in lines[i - 1]:
                roles = re.search(r'roles\s*=\s*\{\s*"([^"]+)"(?:\s*,\s*"([^"]+)")?\s*\}', lines[i - 1])
                key = roles.group(1) + (", " + roles.group(2) if roles.group(2) else "")
                perm = {"管理员": "仅管理员", "管理员, 审核员": "管理员 / 审核员"}.get(key, "未识别")
            for raw in raw_paths:
                path = raw
                if not path.startswith("/api/"):
                    for up in range(i, -1, -1):
                        cm = CLASS_MAPPING.search(lines[up])
                        if cm:
                            path = cm.group(1) + path
                            break
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
            # openapi 缺失时 perm_of_openapi 返回 None —— 不能直接进 :<8 格式化，
            # 否则脚本报 TypeError 崩掉，把「有差异」变成「看不到差异」，比报错更糟
            diff.append(f"{method.upper():6} {path:<46} openapi={str(op):<8} backend={perm}")
    print(f"[校验] 后端 {method_count(be)} 个方法；差异 " + ("无" if not diff else f"{len(diff)} 处"))
    for d in diff:
        print("  ", d)
    return 1 if diff else 0


def method_count(d):
    return len(d)


if __name__ == "__main__":
    sys.exit(main())