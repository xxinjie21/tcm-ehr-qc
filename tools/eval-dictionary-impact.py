"""标准化数据集体检器：把「抽取质量」与「归一质量」分开量化，并评估词典改动的影响。

分两块能力（批次 19）：

① 体检（默认，无需参数）：一次性报出
   - 甲类 · 标准符合度：各类型词典规模、来源、code 覆盖率、别名质量、跨类重名
   - 乙类 · 数据集覆盖度（**下限验证，不是目标**）：归一率、可归一实体归一率、
     抽取碎片率、分类错放率、口语未处理率、质控封顶率、模板塌缩度
   为什么要拆开：归一是串联链路的最后一环，抽取不到的实体归一层根本看不到
   （见 docs/多批次实施计划.md §0.0）。混在一个「归一率」里会误判成词表不够。

② 词典影响评估（`--new-dir`，原有能力保持不变）：比较改词典前/后的命中率。

只读 structured_data 与词典文件，**不碰 ES、不改库**。

已知偏差：真实链路先经 ES 召回（`RECALL_SIZE=50`），本脚本对全部词条判定
→ 归一率是**上限**，比线上略乐观。

用法：
    python tools/eval-dictionary-impact.py              # 只跑体检
    python tools/eval-dictionary-impact.py --new-dir /tmp/new --limit 15
"""
import argparse
import json
import os
import re
import sys
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT_DIR = os.path.join(ROOT, 'data', 'dictionaries')

# 判定为「抽取碎片」的长度上限：归一失败且术语短到不可能是完整标准词
FRAGMENT_MAX_LEN = 2
# 判定为「分类错放」的前缀：脉/舌 开头的要素不该出现在 symptoms 里
MISROUTED_PREFIX = ('脉', '舌')
# 判定为「体征错放」的关键字：压痛/触痛 等属体征，不是症状
PHYSICAL_SIGN = ('压痛', '触痛', '叩痛', '反跳痛')

# structured_data 里的字段名 → 词典文件（与 EntityNormalizer 的字段映射一致）
FIELD_TO_FILE = {
    'diseases': 'diseases.json',
    'symptoms': 'symptoms.json',
    'patternList': 'patterns.json',
    'herbs': 'herbs.json',
    'formulaList': 'formulas.json',
}
# herbs 的归一目标取 name，其余取 content
NAME_FIELD = {'herbs': 'name'}
SCORE_THRESHOLD = 0.8


def die(msg):
    print('[错误] ' + msg, file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 判定（对齐 EsTermNormalizer.judge）

def dice(a, b):
    """字符集合 Dice：2*|A∩B| / (|A|+|B|)"""
    if not a or not b:
        return 0.0
    sa, sb = set(a), set(b)
    return 2.0 * len(sa & sb) / (len(sa) + len(sb))


def judge(entries, term):
    """三级判定：①精确 ②双向包含（多命中取最短标准词）③Dice ≥ 阈值。返回 (标准词, 层级) 或 (None, 0)"""
    # 一级·精确
    for e in entries:
        if e['standardTerm'] == term or term in (e.get('aliases') or []):
            return e['standardTerm'], 1
    # 二级·双向包含，多命中取最短标准词
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
    # 三级·模糊取最高分
    top, top_s = None, 0.0
    for e in entries:
        s = dice(term, e['standardTerm'])
        for a in (e.get('aliases') or []):
            s = max(s, dice(term, a))
        if s > top_s:
            top, top_s = e, s
    if top is not None and top_s >= SCORE_THRESHOLD:
        return top['standardTerm'], 3
    return None, 0


# ---------------------------------------------------------------- 数据与词典

def load_dicts(directory):
    out = {}
    for field, fname in FIELD_TO_FILE.items():
        path = os.path.join(directory, fname)
        out[field] = json.load(open(path, encoding='utf-8')) if os.path.isfile(path) else []
    return out


def load_corpus():
    """从 MySQL 读 structured_data，抽出每类实体。

    返回 (corpus, meta)：
      corpus[field] = [(原文, normLevel), ...]        —— 原有，供词典影响评估
      meta 保留整份 structured_data 原始记录，另供体检用（碎片/错放/口语需跨字段看）
    """
    try:
        import pymysql
    except ImportError:
        die('缺少依赖 pymysql，请先安装：pip install pymysql')
    conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456',
                           database='tcm_ehr', charset='utf8mb4')
    cur = conn.cursor()
    cur.execute("select structured_data from records where structured_data is not null and structured_data <> ''")
    rows = [r[0] for r in cur.fetchall()]
    conn.close()

    corpus = {f: [] for f in FIELD_TO_FILE}
    docs = []
    for raw in rows:
        try:
            data = json.loads(raw)
        except Exception:
            continue
        docs.append(data)
        for field in FIELD_TO_FILE:
            for ent in (data.get(field) or []):
                if not isinstance(ent, dict):
                    continue
                term = (ent.get(NAME_FIELD.get(field, 'content')) or ent.get('sourceText') or '').strip()
                if term:
                    corpus[field].append((term, ent.get('normLevel')))
    return corpus, docs


def load_records_extra():
    """读质控结果与原始字段，用于封顶率、模板塌缩度、口语占比。

    与 load_corpus 分开是因为它读的是记录级列（score/qc_results/原始文本），
    不是 structured_data 里的实体数组。
    """
    try:
        import pymysql
    except ImportError:
        die('缺少依赖 pymysql，请先安装：pip install pymysql')
    conn = pymysql.connect(host='localhost', port=3306, user='root', password='123456',
                           database='tcm_ehr', charset='utf8mb4')
    cur = conn.cursor()
    cur.execute("select structured_data, score, qc_results, self_report, chief_complaint from records")
    rows = cur.fetchall()
    conn.close()
    return rows


def pct(part, total):
    return f'{part / total:.1%}' if total else '—'


# ================================================================ 体检

def check_dict_quality(dicts):
    """甲类 · 标准符合度：词典规模 / 来源 / code 覆盖率 / 别名质量 / 跨类重名。

    只描述词典文件本身，不涉及任何数据集 —— 换数据集这一节不变。
    """
    print('=' * 70)
    print('【甲类】标准符合度（真正的目标，与用哪份数据无关）')
    print('=' * 70)

    all_terms = {}
    for field, entries in dicts.items():
        if not entries:
            print(f'[{field}] 词典为空  ← 需按标准术语集补建')
            continue
        total = len(entries)
        with_code = sum(1 for e in entries if (e.get('code') or '').strip())
        with_alias = sum(1 for e in entries if e.get('aliases'))
        sources = Counter((e.get('source') or '').strip() or '(无来源)' for e in entries)

        # 别名质量：与标准词相同的别名是自命中噪声
        self_alias = sum(1 for e in entries
                         if any(a == e['standardTerm'] for a in (e.get('aliases') or [])))
        # 别名定义文字误入：以中文句号结尾，是释义不是别名
        alias_prose = sum(1 for e in entries
                          for a in (e.get('aliases') or []) if a.endswith('。'))
        # 跨类重名：同一术语出现在多本词典里，类型判定会歧义
        for e in entries:
            all_terms.setdefault(e['standardTerm'], set()).add(field)

        print(f'[{field}] {total} 条  |  code {with_code}（{pct(with_code, total)}）'
              f'  |  有别名 {with_alias}（{pct(with_alias, total)}）')
        print(f'   来源: {"、".join(f"{s}×{c}" for s, c in sources.most_common(2))}')
        if self_alias or alias_prose:
            problems = []
            if self_alias:
                problems.append(f'自别名 {self_alias} 条（归一会自命中）')
            if alias_prose:
                problems.append(f'别名混入定义文字 {alias_prose} 条')
            print(f'   ! 别名缺陷: {"、".join(problems)}')

    dup = {t: ts for t, ts in all_terms.items() if len(ts) > 1}
    if dup:
        sample = '、'.join(list(dup)[:8])
        print(f'! 跨类重名 {len(dup)} 个术语出现在多本词典（类型判定歧义）: {sample}')
    else:
        print('跨类重名: 无')
    print('-' * 70)


def check_corpus_quality(corpus, rows, dicts):
    """乙类 · 数据集覆盖度（**下限验证，不是目标**）。

    核心是把「归一失败」拆成四类，因为它们的责任方完全不同：
      抽取不到 / 抽到错类 / 抽到没归一（词表问题）/ 归一质量差
    """
    print('=' * 70)
    print('【乙类】数据集覆盖度（下限验证，不是目标；换数据集这节会变）')
    print('=' * 70)

    for field, items in corpus.items():
        if not items:
            print(f'[{field}] 未抽取到任何实体')
            continue
        total = len(items)
        hit = sum(1 for _, lv in items if lv)
        print(f'[{field}] 实体 {total} 条  |  线上归一 {hit}（{pct(hit, total)}）')

    print()
    print('—— 未归一实体的构成（责任方各不相同，不能混看）——')
    frag = misroute = physical = 0
    total_unmatched = 0
    for term, lv in corpus['symptoms']:
        if lv:
            continue
        total_unmatched += 1
        # 顺序要紧：体征（压痛）也可能以「脉」开头式的误判，所以先判更具体的形态。
        # 但「脉/舌」是更强的信号（舌象脉象本就不该进 symptoms），故仍以它优先。
        if len(term) <= FRAGMENT_MAX_LEN:
            frag += 1
        elif any(p in term for p in PHYSICAL_SIGN):
            physical += 1
        elif term.startswith(MISROUTED_PREFIX):
            misroute += 1
    print(f'症状未归一 {total_unmatched} 条：')
    print(f'   抽取碎片（len≤{FRAGMENT_MAX_LEN}）        {frag:>5}（{pct(frag, total_unmatched)}）'
          f'  ← 责任：抽取侧')
    print(f'   分类错放（脉/舌进了症状）      {misroute:>5}（{pct(misroute, total_unmatched)}）'
          f'  ← 责任：抽取侧')
    print(f'   体征错放（压痛等进了症状）      {physical:>5}（{pct(physical, total_unmatched)}）'
          f'  ← 责任：抽取侧')
    other = total_unmatched - frag - misroute - physical
    print(f'   其余（多为标准词，属词表问题）  {other:>5}（{pct(other, total_unmatched)}）'
          f'  ← 责任：词表侧')

    # 「可归一」= 不是抽取缺陷（碎片 / 分类错放 / 体征错放）
    def normalizable(term):
        return (len(term) > FRAGMENT_MAX_LEN
                and not any(p in term for p in PHYSICAL_SIGN)
                and not term.startswith(MISROUTED_PREFIX))

    # 可归一实体归一率：分母只算「已抽取 + 非抽取缺陷 + 确属标准症状词」。
    # 「确属标准症状词」需要拿症状词典反查 —— 词表里没有的（如 神疲乏力）本身就是词表缺口，
    # 算进来会把「补词表能解决的」和「词表根本没收录的」混成一个数，看不出该做什么。
    symptom_dict = {e['standardTerm'] for e in dicts.get('symptoms', [])}
    symptom_alias = {a for e in dicts.get('symptoms', []) for a in (e.get('aliases') or [])}

    def standard_symptom(term):
        return term in symptom_dict or term in symptom_alias

    norm_pool = [(t, lv) for t, lv in corpus['symptoms']
                 if normalizable(t) and standard_symptom(t)]
    pool_hit = sum(1 for _, lv in norm_pool if lv)
    if norm_pool:
        print()
        print(f'可归一实体归一率（分母 = 已抽取、非抽取缺陷、且已在症状词表内）: '
              f'{pool_hit}/{len(norm_pool)} = {pct(pool_hit, len(norm_pool))}')
        print('   注：词表里没有的症状（如 神疲乏力）不算进分母 —— 它们是词表缺口本身，')
        print('       正是批次 21 要补的对象；补进来会让这个指标看不出「补词表能改善多少」。')

    # 口语占比在 check_dataset_shape 里用 self_report 列反查（更准，这里不重复算）

    check_dataset_shape(rows)


def check_dataset_shape(rows):
    """数据集形态：模板塌缩度 + 口语占比 + 质控封顶率。"""
    print()
    print('—— 数据集形态（判断当前基准能不能代表真实病历）——')
    total = len(rows)
    # 模板塌缩：把数字全换成 N 之后，主诉还剩几种。合成数据一旦过模板，种类会远少于记录数
    chief = set()
    for _, _, _, _, chief_complaint in rows:
        if isinstance(chief_complaint, str) and chief_complaint.strip():
            chief.add(re.sub(r'\d+', 'N', chief_complaint.strip()))
    if total:
        ratio = len(chief) / total
        print(f'记录 {total} 条，主诉去数字后仅 {len(chief)} 种（{pct(ratio, 1)}）'
              f'{"  ← 模板生成的数据，不能代表真实病历" if ratio <= 0.2 else ""}')

    # 口语：症状原文能在 self_report 里找到 → 来自患者口语，标准词表通常不收
    oral = 0
    for raw, _, _, self_report, _ in rows:
        try:
            data = json.loads(raw) if isinstance(raw, str) else raw
        except Exception:
            continue
        if not isinstance(data, dict) or not isinstance(self_report, str):
            continue
        for ent in (data.get('symptoms') or []):
            if not isinstance(ent, dict) or ent.get('normLevel'):
                continue
            st = ent.get('sourceText') or ent.get('content') or ''
            if st and st in self_report:
                oral += 1
                break
    if total:
        print(f'症状未归一中来自患者口语字段的记录: {oral}（{pct(oral, total)}）'
              f'  ← 标准词表不收口语，勿当成词表缺口')

    # 封顶率：术语未标准化扣分撞 cap(5) 的比例
    capped = scored = 0
    for _, score, qc, _, _ in rows:
        if not isinstance(qc, str) or score is None:
            continue
        try:
            data = json.loads(qc)
        except Exception:
            continue
        scored += 1
        for d in (data.get('deductions') or []):
            if isinstance(d, dict) and d.get('type') == '术语未标准化' \
                    and (d.get('points') or 0) >= 5:
                capped += 1
                break
    if scored:
        print(f'质控封顶率（术语未标准化扣满 5 分）: {capped}/{scored} = {pct(capped, scored)}'
              f'{"  ← 多数病历扣分相同，评分失去区分度" if capped > scored * 0.3 else ""}')
    print('-' * 70)


def main():
    ap = argparse.ArgumentParser(description='标准化数据集体检器 + 词典影响离线评估（只读，不碰 ES / 不改库）')
    ap.add_argument('--old-dir', default=DICT_DIR, help='旧词典目录（默认 data/dictionaries）')
    ap.add_argument('--new-dir', help='新词典目录；给了才跑「词典影响评估」，不给就跑体检')
    ap.add_argument('--limit', type=int, default=12, help='可疑样本打印条数')
    args = ap.parse_args()

    old = load_dicts(args.old_dir)

    # ① 体检（默认行为）：甲类标准符合度 + 乙类数据集覆盖度
    corpus, _docs = load_corpus()
    rows = load_records_extra()
    check_dict_quality(old)
    check_corpus_quality(corpus, rows, old)

    # ② 词典影响评估（原有能力，需 --new-dir）
    if not args.new_dir:
        print('提示：评估词典改动效果请加 --new-dir <新词典目录>')
        return

    new = load_dicts(args.new_dir)
    print('=' * 70)
    for field, fname in FIELD_TO_FILE.items():
        items = corpus[field]
        if not items:
            continue
        o, n = old[field], new[field]
        if not n:
            continue
        stat = {'old': Counter(), 'new': Counter(), 'prod': Counter()}
        suspect = []
        for term, prod_level in items:
            stat['prod']['hit' if prod_level else 'miss'] += 1
            for tag, d in (('old', o), ('new', n)):
                std, lv = judge(d, term)
                stat[tag]['hit' if lv else 'miss'] += 1
                stat[tag][f'L{lv}'] += 1
                if tag == 'new' and lv in (2, 3) and std and len(std) * 2 < len(term):
                    suspect.append((term, std, lv))
        total = len(items)
        print(f'[{field}] 实体 {total} 条  |  词典 {len(o)} -> {len(n)} 条')
        print(f'   线上实际  命中 {stat["prod"]["hit"]:>5}  ({stat["prod"]["hit"] / total:.1%})')
        print(f'   旧词典模拟 命中 {stat["old"]["hit"]:>5}  ({stat["old"]["hit"] / total:.1%})'
              f'  精确 {stat["old"]["L1"]} / 包含 {stat["old"]["L2"]} / 模糊 {stat["old"]["L3"]}')
        print(f'   新词典模拟 命中 {stat["new"]["hit"]:>5}  ({stat["new"]["hit"] / total:.1%})'
              f'  精确 {stat["new"]["L1"]} / 包含 {stat["new"]["L2"]} / 模糊 {stat["new"]["L3"]}')
        if suspect:
            print(f'   ! 可疑（标准词不足原文一半长，共 {len(suspect)} 条，前 {args.limit}）：')
            for term, std, lv in suspect[:args.limit]:
                print(f'       {term} -> {std}  (L{lv})')
        print('-' * 70)


if __name__ == '__main__':
    main()
