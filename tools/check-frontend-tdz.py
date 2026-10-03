import io, re, glob

"""
TDZ 静态体检 —— 批次 12 工作项 8「TDZ 调序」的自动化实现。

起因：批次 17 我把 `TYPE_OPTIONS = Object.keys(TYPE_LABELS)` 插到了 TYPE_LABELS
声明之前，页面白屏（ReferenceError: Cannot access 'TYPE_LABELS' before
initialization），而 `vite build` 一直通过、vue-tsc 也不会报（ESM 顶层 const
在块内提升但未初始化）。

判定规则（两个都必须满足才算风险）：
  1. 引用的位置在该顶层 const 的声明**之前**；
  2. 该引用位于**立即求值**位置 —— 即引用之前没有 `=>`（箭头函数体是延迟求值，
     computed(() => X) 里的 X 安全）。
`function` 声明会提升，视为永远安全。

用法：python tools/check-frontend-tdz.py；有输出即为疑似问题，逐条人工确认。
"""
import sys
import io
import re
import glob
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
FILES = sorted(
    glob.glob(os.path.join(ROOT, 'frontend/src/**/*.vue'), recursive=True)
    + glob.glob(os.path.join(ROOT, 'frontend/src/**/*.js'), recursive=True)
)

DECL_RE = re.compile(r'^(const|let)\s+(\w+)\s*=')
DESTRUCT_RE = re.compile(r'^(?:const|let)\s*\{([^}]*)\}\s*=')
FUNC_RE = re.compile(r'^function\s+(\w+)\s*\(')


def analyse(src, label):
    problems = []
    lines = src.split('\n')

    # 1) 收集声明位置：const/let 会被 TDZ 卡；function 提升，安全
    decl = {}     # name -> 行索引
    hoisted = set()
    for i, l in enumerate(lines):
        m = DECL_RE.match(l)
        if m:
            decl.setdefault(m.group(2), i)
            continue
        m = DESTRUCT_RE.match(l)
        if m:
            for nm in m.group(1).split(','):
                nm = nm.split(':')[-1].split('=')[0].strip()
                if re.fullmatch(r'\w+', nm):
                    decl.setdefault(nm, i)
            continue
        m = FUNC_RE.match(l)
        if m:
            hoisted.add(m.group(1))

    for i, l in enumerate(lines):
        m = DECL_RE.match(l)
        if not m:
            continue
        # 先剥掉字符串字面量与模板串，否则 ref('query') 里的 'query' 会被当成变量引用
        expr = l.split('=', 1)[1]
        expr = re.sub(r"'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\"|`(?:[^`\\]|\\.)*`", ' ', expr)
        for name, dline in decl.items():
            if name in hoisted:
                continue
            if dline <= i:          # 声明在前 → 安全（这条就是之前写反的地方）
                continue
            mm = re.search(r'(?<![\w.$])' + re.escape(name) + r'(?![\w$])', expr)
            if not mm:
                continue
            before_arrow = expr[:mm.start()]
            if '=>' in before_arrow:
                continue           # 在箭头函数体里 → 延迟求值 → 安全
            problems.append((label, i + 1, name, dline + 1, l.strip()[:86]))
    return problems


all_problems = []
scanned = 0
for f in FILES:
    s = io.open(f, encoding='utf-8').read()
    if f.endswith('.vue'):
        if '<script setup' not in s:
            continue
        scr = s.split('<script setup', 1)[1].split('</script>', 1)[0]
    else:
        scr = s
    scanned += 1
    all_problems += analyse(scr, os.path.relpath(f, ROOT).replace('\\', '/'))

print('已扫描 %d 个前端文件' % scanned)
if all_problems:
    print('!! 疑似 TDZ（顶层初始化式立即引用了后声明的 const/let）')
    for label, uline, name, dline, txt in all_problems:
        print('  %-30s 行%d 用了 %s，但它声明在行%d' % (label, uline, name, dline))
        print('        %s' % txt)
    sys.exit(1)
print('OK 未发现顶层 TDZ 隐患')