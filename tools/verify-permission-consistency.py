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
# 括号可省略：`@PostMapping`（无参）也是合法写法，路径就是类级前缀本身。
# 原正则强制要求 ( )，导致 StatsController.stats 这种无参映射被整条漏检。
ANY_MAPPING = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\((.*)\))?')
QUOTED = re.compile(r'"([^"]+)"')
CLASS_MAPPING = re.compile(r'@RequestMapping\("([^"]+)"\)')


def paths_of(line):
    """一行注解里的全部路径（单值 / 数组都支持）。

    ⚠️ 空相对路径与无参注解都算「有一个路径 = 类级前缀本身」，必须返回 [""]：
    批次 10 把 Controller 统一成「类级前缀 + 方法级相对路径」后，出现了
    `@GetMapping("")`（= 前缀根，如 /api/logs）与无参 `@PostMapping`（= /api/stats）。
    原实现对这两种返回空列表，调用方 `if not raw_paths: continue` 就把端点整个跳过 ——
    校验照样报「0 差异」，而这几个端点从未被比对过。静默漏检比报错糟得多。
    """
    m = ANY_MAPPING.search(line)
    if not m:
        return None, []
    paths = QUOTED.findall(m.group(2) or "")
    if not paths:
        # `@PostMapping`（无括号）或 `@GetMapping("")` —— 都表示类级前缀本身
        return m.group(1).lower(), [""]
    return m.group(1).lower(), paths


def perm_of_nearby_annotations(lines, i):
    """取 @Mapping 附近的权限标注（上下两个方向都扫）。

    ⚠️ 不能只看 lines[i-1]（紧邻的上一行）：方法上方有 javadoc、或是把
    @RequireRole 写在 @Mapping 之下，都会读空，然后默认成「登录即���」而
    **不报差异** —— 这种静默漏检比报错糟得多：批次 8b 加
    POST /api/dictionary/reindex 时真实踩到，明明写了 @RequireRole，
    校验却报「登录即可」，差点把管理员接口当登录即可提交。
    做法：跨过空行、注释行、块注释与其它注解，上下各找一条权限标注。
    """
    for step in (-1, 1):
        for d in range(1, 40):
            j = i + step * d
            if j < 0 or j >= len(lines):
                break
            t = lines[j].strip()
            if not t or t.startswith("*") or t.startswith("/*") \
                    or t.startswith("//") or t.startswith("*/"):
                continue
            if t.startswith("@"):
                if "@RequireOrgRole" in t:
                    return "所有者（本组织）"
                if "@RequireRole" in t:
                    m = re.search(r'roles\s*=\s*\{\s*"([^"]+)"(?:\s*,\s*"([^"]+)")?\s*\}', t)
                    if m:
                        key = m.group(1) + (", " + m.group(2) if m.group(2) else "")
                        return {"管理员": "仅管理员", "管理员, 审核员": "管理员 / 审核员"}.get(key, "未识别")
                    return "未识别"
                continue
            # 遇到普通代码行：向上是上一段方法的尾巴、向下是本方法体，都说明标注不在这一段
            break
    return "登录即可"


def collapse_annotations(lines):
    """把跨行写的 @XxxMapping(...) 拼成一行（其余行置空，保持行号对齐）。

    ⚠️ 必须做这一步：原实现逐行匹配 `@XxxMapping(...)`，而 `(.*)` 要求右括号同在该行。
    OrgController 的双路径数组就是这么写的：

        @PutMapping({"/api/orgs/{id}/members/{userId}/permissions",
                     "/api/groups/{id}/members/{userId}/permissions"})

    该行没有右括号 → 可选括号组失配 → 路径取空 → 落成 `[""]` → 拼上类级前缀后
    不以 `/api/` 开头 → **整条端点被静默跳过**，从未参与比对。
    批次 6 补 openapi 的 permissions 端点时，正是反向检查（openapi 有、后端没有）
    把这个盲区暴露出来的。
    """
    out = list(lines)
    for i, line in enumerate(out):
        if not re.search(r'@(Get|Post|Put|Delete|Patch)Mapping\s*\(', line):
            continue
        if line.count("(") <= line.count(")"):
            continue
        merged = line
        j = i + 1
        while j < len(out) and merged.count("(") > merged.count(")"):
            merged += " " + out[j].strip()
            out[j] = ""
            j += 1
        out[i] = merged
    return out


def collect_backend():
    out = {}
    for p in BACKEND.rglob("*.java"):
        lines = collapse_annotations(io.open(p, encoding="utf-8").read().split("\n"))
        for i, line in enumerate(lines):
            method, raw_paths = paths_of(line)
            if not method or not raw_paths:
                continue
            perm = perm_of_nearby_annotations(lines, i)
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


def openapi_structure_errors():
    """契约文档自身的机械自洽：YAML 可解析 + 每个 $ref 有定义且没被折叠/错位。

    ⚠️ 为什么这两条必须进来（都是本脚本按行正则**扫不出来**的）：
      1) 2026-10-03 的 23d4b149 在补 8 个接口时，把
         `$ref: '#/components/schemas/DictionaryTermsVO'` 劈成两半（头片段留在原行、
         尾片段成了顶格孤儿行），此后整份 openapi 一直是**非法 YAML**，
         而本脚本照报「0 差异」—— 契约文档连续 3 天无法被任何解析器读取。
      2) `/api/qc/score/batch` 的 requestBody 引 `QcBatchDTO`，而该 schema 从未定义；
         悬空引用同样一条都报不出来。

    ⚠️ 只做「劈开的两半是否在同一行」的正则判断是不够的：YAML 的**单引号标量可以跨行折叠**，
    `$ref: '#/component` + 换行 + `s/schemas/X'` 依然能解析成功，只是值变成了
    `#/component s/schemas/X`（带空格）—— 正则找不到 `#/components/` 也就当它不存在。
    所以这里以**解析结果为准**：把解析树里所有 $ref 拿出来，逐个要求「形如
    #/components/<段>/<名>」且目标存在。另外把文本里 `$ref:` 的出现次数与解析到的个数对账，
    作为「引用被折叠/缩进吞掉」的兜底（折叠成合法标量时两边相等，靠上面的解析检查兜住）。

    ⚠️ 能力边界（别把它当万能）：把 `$ref:` 改写成别的键名属于语义改动，
    机械校验看不出来；这里只保证「引用写得出来、指得到」。

    只报告、不改文件。缺 PyYAML 时退回纯正则的悬空检查并提示（不给校验脚本引入硬依赖）。
    """
    text = io.open(OPENAPI, encoding="utf-8-sig").read()
    errs = []
    try:
        import yaml
    except ImportError:
        print("[提示] 未安装 PyYAML，openapi 只做正则悬空检查（pip install pyyaml 可全量校验）")
        return _regex_dangling_refs(text, errs)

    try:
        doc = yaml.safe_load(text)
    except Exception as e:  # 解析器异常类型随实现而变，一律当契约损坏
        errs.append("openapi 不是合法 YAML：" + str(e).split("\n")[0])
        return errs

    refs = []

    def walk(node):
        if isinstance(node, dict):
            for k, v in node.items():
                if k == "$ref" and isinstance(v, str):
                    refs.append(v)
                else:
                    walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(doc)
    for r in sorted(set(refs)):
        parts = r.split("/")
        ok = (r.startswith("#/components/") and len(parts) == 4 and all(parts[1:]))
        if ok:
            cur = doc
            for seg in parts[1:]:
                if not isinstance(cur, dict) or seg not in cur:
                    ok = False
                    break
                cur = cur[seg]
        if not ok:
            errs.append(f"$ref 无法解析：{r!r}")

    seen = len(re.findall(r"\$ref:", text))
    if seen != len(refs):
        errs.append(
            f"$ref 计数不符：文本 {seen} 处、解析到 {len(refs)} 处"
            "（多出的引用被折叠进标量或错位到别的层级）"
        )
    return errs


def _regex_dangling_refs(text, errs):
    """无 PyYAML 时的退路：只查 `#/components/schemas/X` 的 X 有没有定义。"""
    i = text.find("\n  schemas:")
    if i < 0:
        errs.append("openapi 缺少 components.schemas 段")
        return errs
    body = text[i + 1:]
    top = re.search(r"^\S", body, re.M)
    schemas = body[:top.start()] if top else body
    defined = set(re.findall(r"^    ([A-Za-z0-9_.-]+):\s*$", schemas, re.M))
    for name in sorted(set(re.findall(r"#/components/schemas/([A-Za-z0-9_.-]+)", text))):
        if name not in defined:
            errs.append(f"$ref 悬空：#/components/schemas/{name} 未定义")
    return errs


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

    # 反向：openapi 声明了、后端却没有的端点。
    # 只做单向比对时，「后端端点被改名/写漏」这类问题永远看不见 ——
    # 批次 10 改 Controller 路由风格时，端点计数从 74 掉到 71 就是这么藏住的。
    missing = missing_in_backend(be)
    for path, method in sorted(missing):
        diff.append(f"{method.upper():6} {path:<46} openapi=已声明   backend=缺失")

    # 契约文档自身的机械自洽（理由见 openapi_structure_errors 的注释）
    diff.extend(openapi_structure_errors())

    print(f"[校验] 后端 {method_count(be)} 个方法；差异 " + ("无" if not diff else f"{len(diff)} 处"))
    for d in diff:
        print("  ", d)
    return 1 if diff else 0


def missing_in_backend(be):
    """openapi 里有、后端没实现的 (path, method)。"""
    text = io.open(OPENAPI, encoding="utf-8-sig").read()
    body = text[text.find("\npaths:"):]
    have = {(p, m.upper()) for (p, m) in be}
    out = set()
    for pm in re.finditer(r"^  (/\S*):\s*$", body, re.M):
        blk = body[pm.end():]
        nxt = blk.find("\n  /")
        blk = blk[:nxt] if nxt != -1 else blk
        for verb in re.findall(r"^    (get|post|put|delete|patch):", blk, re.M):
            key = (pm.group(1), verb.upper())
            if key not in have:
                out.add(key)
    return out


def method_count(d):
    return len(d)


if __name__ == "__main__":
    sys.exit(main())