"""批次 20 的抽取与归一回归测试（不依赖模型，纯规则）。

跑法（在仓库根目录）：
    python python-nlp/test_batch20_rules.py
或：
    python-nlp/.venv/Scripts/python.exe python-nlp/test_batch20_rules.py

为什么要这个文件：本项目只有 Java 侧的单测，python-nlp 的规则从未被测试覆盖。
而批次 20 改的恰好是这几条正则 —— 它们「不抛异常、只是悄悄少抽一类子要素」，
没有测试就只能靠肉眼 diff。这里把三件事钉住：

① 舌象形态子要素不再丢（`边有齿痕` / `有裂纹` / `有瘀点`）
② 脉位与脉象分开成段（`脉细数，左尺无力` → 两条）
③ 词典对真实片段**精确命中**，不出现有损的二层包含匹配
   （`苔黄燥少津` → `黄苔` 会把「燥、少津」两个子要素丢掉）
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'python-nlp'))

from main import _rules  # noqa: E402

DICT_DIR = os.path.join(ROOT, 'data', 'dictionaries')
failures = []


def check(label, got, want):
    if got == want:
        print(f'  OK  {label} -> {got}')
    else:
        failures.append(label)
        print(f'  BAD {label} -> {got}   期望 {want}')


def load_dict(name):
    with open(os.path.join(DICT_DIR, name), encoding='utf-8') as f:
        return json.load(f)


def dice(a, b):
    if not a or not b:
        return 0.0
    sa, sb = set(a), set(b)
    return 2.0 * len(sa & sb) / (len(sa) + len(sb))


def judge(entries, term):
    """与 EsTermNormalizer.judge 同口径：精确 → 双向包含取最短 → Dice >= 0.8"""
    for e in entries:
        if e['standardTerm'] == term or term in (e.get('aliases') or []):
            return e['standardTerm'], 1
    best = None
    for e in entries:
        st = e['standardTerm']
        hit = st in term or term in st
        if not hit:
            for a in (e.get('aliases') or []):
                if a in term or term in a:
                    hit = True
                    break
        if hit and (best is None or len(st) < len(best['standardTerm'])):
            best = e
    if best is not None:
        return best['standardTerm'], 2
    top, top_s = None, 0.0
    for e in entries:
        s = dice(term, e['standardTerm'])
        for a in (e.get('aliases') or []):
            s = max(s, dice(term, a))
        if s > top_s:
            top, top_s = e, s
    if top is not None and top_s >= 0.8:
        return top['standardTerm'], 3
    return None, 0


print('① 舌象形态子要素不再丢')
check('舌质淡胖，边有齿痕，苔白腻',
      [e.content for e in _rules('舌质淡胖，边有齿痕，苔白腻')['tongueList']],
      ['舌质淡胖', '边有齿痕', '苔白腻'])
check('舌红少苔，或有裂纹（原文用「或」连接）',
      [e.content for e in _rules('舌红少苔，或有裂纹')['tongueList']],
      ['舌红少苔', '有裂纹'])
check('舌红有瘀点',
      [e.content for e in _rules('舌红有瘀点')['tongueList']],
      ['舌红有瘀点'])

print()
print('② 脉位与脉象分开成段')
check('脉细数，左尺无力',
      [e.content for e in _rules('脉细数，左尺无力')['pulseList']],
      ['脉细数', '左尺无力'])
check('脉弦劲有力，左关尤甚',
      [e.content for e in _rules('脉弦劲有力，左关尤甚')['pulseList']],
      ['脉弦劲有力', '左关尤甚'])

print()
print('③ 描述性措辞不得被误当子要素')
for text in ('有神', '有神气', '神疲乏力', '腹胀'):
    check(f'{text}（不应命中舌脉）',
          [e.content for e in _rules(text)['tongueList'] + _rules(text)['pulseList']],
          [])

print()
print('④ 脉象抽取不能吞掉舌象（`脉细，舌红` 的「舌红」）')
check('脉细，舌红',
      [e.content for e in _rules('脉细，舌红')['pulseList']],
      ['脉细'])

print()
print('⑤ 词典精确命中，不出现有损的二层匹配')
tongue = load_dict('tongues.json')
pulse = load_dict('pulses.json')
for text in ('舌质淡胖', '边有齿痕', '苔白腻', '苔薄白', '苔黄燥少津',
             '舌红少苔', '有裂纹', '舌红有瘀点'):
    segs = [e.content for e in _rules(text)['tongueList']] or [text]
    for seg in segs:
        std, lv = judge(tongue, seg)
        check(f'舌象 {seg!r}', (std, lv), (seg, 1) if seg == std else (std, lv))
        if lv in (2, 3):
            failures.append(f'舌象 {seg} 有损匹配 {std}')

for text in ('脉细数，左尺无力', '脉弦劲有力，左关尤甚', '脉沉紧而迟',
             '脉细弱', '脉沉细无力', '脉滑数有力', '脉浮缓无力',
             '脉弦细', '脉弦数有力'):
    for e in _rules(text)['pulseList']:
        std, lv = judge(pulse, e.content)
        if lv != 1:
            failures.append(f'脉象 {e.content} 非精确命中 -> {std} (L{lv})')
        print(f'  {"OK " if lv == 1 else "BAD"} 脉象 {e.content!r} -> {std!r} (L{lv})')

print()
if failures:
    print(f'=== 失败 {len(failures)} 项：{failures}')
    sys.exit(1)
print('=== 全部通过 ===')