"""抽取产物过滤（单字 / 假药名 / 否定）的回归闸门。

跑法（仓库根目录）：
    python python-nlp/tests/test_noise_filter.py
或走统一闸门：
    python python-nlp/run_tests.py

不依赖模型：直接喂**构造好的** span / 文本，只验 `nlp_filters` 的三条判据。
写成自跑脚本（`check()` + `sys.exit(1)`）而不是 pytest 的 `def test_*`：
`run_tests.py` 是 `subprocess.run([sys.executable, path])` 逐个跑文件的，纯 `def test_*`
不会被收集，等于测试**编过但没跑**（exit 0 假绿）。

这三条判据分别钉住 2026-10-18 全流程实测暴露的三类噪声：
    F1 单字残词   —— `有` / `血` / `双`
    F2 假药名     —— `细胞角蛋白19`（dosage=19片）/ `江西省` / `江西中`
    F3 否定句阳性 —— `反酸` / `嗳气` / `打嗝`（原文均出自「无…」）
"""
import os
import sys

# 本文件在 python-nlp/tests/ 下，上溯两层是 python-nlp/
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from nlp_filters import drop_noise, is_negated_span, is_plausible_herb  # noqa: E402
from schemas import Entity, ExtractResponse  # noqa: E402

failures = []


def check(label, got, want):
    if got == want:
        print(f'  OK  {label} -> {got}')
    else:
        failures.append(label)
        print(f'  BAD {label} -> {got}   期望 {want}')


print('F1 单字残词被丢，双字症状留下')
_resp = ExtractResponse()
_resp.symptoms = [Entity(content='有', sourceText='有'), Entity(content='腰酸', sourceText='腰酸')]
_counts = drop_noise(_resp)
check('单字「有」被丢、双字「腰酸」保留',
      [e.content for e in _resp.symptoms], ['腰酸'])
check('丢弃计数回报 symptoms=1', _counts.get('symptoms'), 1)

print()
print('F2 假药名被丢，真药名留下')
for span in ('细胞角蛋白19', '江西省', '江西中'):
    check(f'{span}（鬼词，不是药）', is_plausible_herb(span), False)
for span in ('岷当归', '土茯苓', '益母草'):
    check(f'{span}（真药名）', is_plausible_herb(span), True)

print()
print('F3 否定：窗口（前文分句）命中 / span 自带 / 非否定')
# 「反酸」的否定词「无」在同段更前面（顿号不隔断辖域）
_text = '纳可，知饥不食，无胃胀、反酸、嗳气、打嗝等'
check('反酸（前文分句有「无」）',
      is_negated_span('反酸', _text, _text.index('反酸'), _text.index('反酸') + 2), True)
check('无黄汗（span 自带否定词）',
      is_negated_span('无黄汗', '汗出偏少，无黄汗', 5, 8), True)
check('腰酸（肯定句，不许误判）',
      is_negated_span('腰酸', '平素腰酸', 2, 4), False)

print()
print('F3 整批过滤：否定症状被丢，规范「不X」症状留下')
_text = '无胃胀、反酸、嗳气、打嗝等。食欲不振，大便不畅，睡眠欠佳。'
_resp = ExtractResponse()
_resp.symptoms = [Entity(content=x, sourceText=x) for x in
                  ['胃胀', '反酸', '嗳气', '打嗝', '食欲不振', '大便不畅', '睡眠欠佳']]
drop_noise(_resp, full_text=_text)
_got = [e.content for e in _resp.symptoms]
check('否定枚举项全部被丢',
      [x for x in ('胃胀', '反酸', '嗳气', '打嗝') if x in _got], [])
check('规范症状（食欲不振 / 大便不畅 / 睡眠欠佳）全部保留',
      [x for x in ('食欲不振', '大便不畅', '睡眠欠佳') if x in _got],
      ['食欲不振', '大便不畅', '睡眠欠佳'])

print()
print('F4 否定泄漏：任意实体类（含疾病）被否定辖域覆盖时剔除')
# 2026-10-18 实测「无脚气」被抽成疾病名 —— 根因是 F3 此前只作用于症状列。
# 「排除肿瘤」走 NEG_HARD 硬模式；「月经不调」含「不调」是规范症状，不许被自否定误伤。
_text = '无脚气，无黄汗。排除肿瘤。月经不调，大便不畅。'
_resp = ExtractResponse()
_resp.diseases = [Entity(content=x, sourceText=x) for x in ['脚气', '肿瘤', '月经不调']]
_resp.symptoms = [Entity(content='大便不畅', sourceText='大便不畅')]
drop_noise(_resp, full_text=_text)
check('被否定的疾病「脚气」「肿瘤」剔除，肯定项「月经不调」保留',
      [e.content for e in _resp.diseases], ['月经不调'])
check('规范症状「大便不畅」（含「不X」）不被误伤',
      [e.content for e in _resp.symptoms], ['大便不畅'])

print()
print('F5 症状形态噪声：含阿拉伯数字 / 以「为主」收尾')
_resp = ExtractResponse()
_resp.symptoms = [Entity(content=x, sourceText=x) for x in
                  ['反复发作5', '头面为主', '经期小腹痛']]
drop_noise(_resp)
check('数字噪声「反复发作5」与「头面为主」被剔除',
      [e.content for e in _resp.symptoms], ['经期小腹痛'])

print()
print('F5b 常态陈述与描述碎片：小便可 / 纳可 / 反复发作 不算症状')
_resp = ExtractResponse()
_resp.symptoms = [Entity(content=x, sourceText=x) for x in
                  ['小便可', '纳可', '二便正常', '反复发作', '头晕']]
drop_noise(_resp)
check('常态陈述与「反复发作」被剔除，真症状「头晕」保留',
      [e.content for e in _resp.symptoms], ['头晕'])

print()
print('F5c 叙事/结局碎片：以「消失 / 好转 / 达全身」收尾不算症状')
# 2026-10-19 实测：「经来痛消失」若归一化会错配到「经行腹痛」（语义反转）；
# 「运动后可汗出达全身」是转归描述，均须整体丢弃。
_resp = ExtractResponse()
_resp.symptoms = [Entity(content=x, sourceText=x) for x in
                  ['经来痛消失', '病情好转', '运动后可汗出达全身', '汗出偏少']]
drop_noise(_resp)
check('叙事碎片被剔除，真症状「汗出偏少」保留',
      [e.content for e in _resp.symptoms], ['汗出偏少'])

print()
if failures:
    print(f'=== 失败 {len(failures)} 项：{failures}')
    sys.exit(1)
print('=== 全部通过 ===')
