"""批次 20 的抽取与归一回归测试（不依赖模型，纯规则）。

跑法（在仓库根目录）：
    python python-nlp/tests/test_batch20_rules.py
或走统一闸门：
    python-nlp/.venv/Scripts/python.exe python-nlp/run_tests.py

为什么要这个文件：本项目只有 Java 侧的单测，python-nlp 的规则从未被测试覆盖。
而批次 20 改的恰好是这几条正则 —— 它们「不抛异常、只是悄悄少抽一类子要素」，
没有测试就只能靠肉眼 diff。这里把这几件事钉住：

① 舌象形态子要素不再丢（`边有齿痕` / `有裂纹` / `有瘀点` / 任务 4 的 `有芒刺` 等）
② 脉位与脉象分开成段（`脉细数，左尺无力` → 两条）；脉位后缀**不许**限定为脉质字
③ 词典对真实片段**精确命中**，不出现有损的二层包含匹配
   （`苔黄燥少津` → `黄苔` 会把「燥、少津」两个子要素丢掉）
④ 病因词表**不许**吸收证候/病名词，治法引导词的扩展（任务 4）
⑤ 脉象 / 舌象要素不留在 `symptoms` 里（`_reroute_pulse_tongue`）

注意 ① 与 ④ 里带「任务 4」的用例：那几条改动在演示数据 500 行上命中 0 次，
**本文件是它们唯一的验证手段**，不是锦上添花的补充测试。
"""
import json
import os
import re
import sys

# 本文件在 python-nlp/tests/ 下，上溯三层才是仓库根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'python-nlp'))

from main import Entity, ExtractResponse, _reroute_pulse_tongue, _rules  # noqa: E402

# 词典的权威位置在 Java 侧 resources 下（原 data/dictionaries 已不存在，
# 路径没跟着搬导致本文件的 ⑤ 段长期以 FileNotFoundError 中断）。
DICT_DIR = os.path.join(ROOT, 'java-backend', 'src', 'main', 'resources', 'dictionaries')
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

# ①b 皮肤病名不得被当舌象（苔藓与舌苔同首字）。2026-10-15 实测存量里有 9 条
#     苔藓样* 误落进 tongueList，根因就是正则只要求首字是「舌」或「苔」。
for text in ('苔藓样皮疹', '苔藓样皮损', '苔藓样大减', '苔藓', '扁平苔藓'):
    check(f'{text}（皮肤病名，不应命中舌象）',
          [e.content for e in _rules(text)['tongueList']],
          [])

# ①c 任务 4 新增的舌象形态锚。演示数据 500 行里这 4 个锚命中 0 次，
#     所以这几条不是「回归测试」而是**行为固化** —— 否则这几行改动等于没人验过。
#     `边有瘀斑` 那条是钉住 `边有` 没被收窄成 `边有齿痕`（收窄后它会整段丢掉）。
for text, want in (('有芒刺', ['有芒刺']),
                   ('边有瘀斑', ['边有瘀斑']),
                   ('剥苔', ['剥苔']),
                   ('无苔', ['无苔']),
                   ('少苔', ['少苔'])):
    check(f'舌象形态锚 {text}',
          [e.content for e in _rules(text)['tongueList']],
          want)

print()
print('② 脉位与脉象分开成段')
check('脉细数，左尺无力',
      [e.content for e in _rules('脉细数，左尺无力')['pulseList']],
      ['脉细数', '左尺无力'])
check('脉弦劲有力，左关尤甚',
      [e.content for e in _rules('脉弦劲有力，左关尤甚')['pulseList']],
      ['脉弦劲有力', '左关尤甚'])
# ②b 上面两条同时是「脉位后缀不许要求是脉质字」的回归守卫。
#     `docs/NLP服务性能与准确率提升计划.md` 的任务 4 提议把 RULE_PULSE_POSITION 收窄成
#     「左/右 + 寸关尺 + 16 个脉质字之一」，而「无力」「尤甚」都不在那 16 个字里 ——
#     照那个写法，上面两条会直接变成空，实测脉象 144 → 120 条次（-16.7%）。
check('右寸沉弱无力（脉质字起头的长后缀）',
      [e.content for e in _rules('右寸沉弱无力')['pulseList']],
      ['右寸沉弱无力'])

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
print('④b 病因词表不得吸收「证候 / 病名」性质的词（2026-10-17 撤回过一次）')
# 这 6 个词在词典里的身份不是病因：`肝郁` 是 patterns.json 里 `肝气郁结` 的别名，
# `食积` 是 diseases.json 的病名，`脾虚`/`肾虚`/`火热`/`水饮` 只作为 patterns 里
# 证候名的组成部分出现。它们该由证候 / 病名两路归一收走（那两条有词典），
# 再当病因抽一遍就是跨类重复计数 —— 所以下面这些用例期望的是**空**。
for text in ('肝郁气滞', '肝郁脾虚', '脾虚证', '肾虚证', '火热证',
             '水饮内停证', '食积发热证'):
    check(f'{text}（证候/病名词，不应落进病因）',
          [e.content for e in _rules(text)['causeList']],
          [])
check('原有病因词照常工作（寒湿 + 痰）',
      [e.content for e in _rules('寒湿痹阻，痰气互结')['causeList']],
      ['寒湿', '痰'])

print()
print('④c 治法引导词扩展（任务 4：补 法宜 / 当予）')
# 演示数据整批连「治」字都没有，treatmentList 恒为空 —— 同样只有这里能验。
check('法宜健脾益气（新增引导词）',
      [e.content for e in _rules('法宜健脾益气')['treatmentList']],
      ['法宜健脾益气'])
check('当予温阳利水（新增引导词）',
      [e.content for e in _rules('当予温阳利水')['treatmentList']],
      ['当予温阳利水'])
check('治以清热利湿（原有引导词不回归）',
      [e.content for e in _rules('治以清热利湿')['treatmentList']],
      ['治以清热利湿'])
check('治法不能吃掉标点后的内容',
      [e.content for e in _rules('治以清热利湿，忌辛辣')['treatmentList']],
      ['治以清热利湿'])

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
print('⑥ 脉象 / 舌象要素不留在 symptoms 里（_reroute_pulse_tongue）')


def _ent(content, source='model'):
    return Entity(content=content, sourceText=content, source=source)


def reroute(symptoms, pulse=(), tongue=()):
    """构造一个只含三类的响应，跑一遍归位，返回 (symptoms, pulseList, tongueList)。"""
    r = ExtractResponse()
    r.symptoms = [_ent(c) for c in symptoms]
    r.pulseList = [_ent(c, 'rule') for c in pulse]
    r.tongueList = [_ent(c, 'rule') for c in tongue]
    _reroute_pulse_tongue(r)
    return ([e.content for e in r.symptoms],
            [e.content for e in r.pulseList],
            [e.content for e in r.tongueList])


# ① 模型把脉/舌标成「症状」，规则侧也抽到了同一个词 → 只留脉象/舌象一处，不重复
check('脉细数已在脉象里 + 症状里也有',
      reroute(['脉细数', '胸闷'], pulse=['脉细数']),
      (['胸闷'], ['脉细数'], []))

# ② 规则没抽到（模型漏放）：移动而不是丢弃 —— 实测这类占错放项的 59%
check('脉沉细只在症状里（规则没抽到）',
      reroute(['脉沉细', '纳差']),
      (['纳差'], ['脉沉细'], []))

# ③ 舌象同理
check('舌红少苔只在症状里',
      reroute(['舌红少苔', '咽痛']),
      (['咽痛'], [], ['舌红少苔']))

# ④ 正常症状一个都不能少（不能被误伤）
check('正常症状全部保留',
      reroute(['鼻塞', '咽痛', '腰酸', '恶寒']),
      (['鼻塞', '咽痛', '腰酸', '恶寒'], [], []))

# ⑤ 已归位过的不会二次重复（幂等）
_r = ExtractResponse()
_r.symptoms = [_ent('脉细数')]
_r.pulseList = [_ent('脉细数', 'rule')]
_reroute_pulse_tongue(_r)
check('重复执行不产生重复项',
      ([e.content for e in _r.symptoms], [e.content for e in _r.pulseList]),
      ([], ['脉细数']))

# ⑥ 皮肤病名不许被当舌脉搬走（_PT_BLACKLIST 的「苔藓」前缀）。
#    模型若把「苔藓样皮疹」标成症状，归位必须把它留在症状里 —— 它是皮肤病，
#    归到 tongueList 反而会被 EntityTypes 的 countsUnnormalized=false 摘出扣分。
check('苔藓样皮疹留在症状里',
      reroute(['苔藓样皮疹', '瘙痒'], tongue=[]),
      (['苔藓样皮疹', '瘙痒'], [], []))
check('股藓/扁平苔藓留在症状里',
      reroute(['股藓', '扁平苔藓', '咽痛']),
      (['股藓', '扁平苔藓', '咽痛'], [], []))

print()
if failures:
    print(f'=== 失败 {len(failures)} 项：{failures}')
    sys.exit(1)
print('=== 全部通过 ===')