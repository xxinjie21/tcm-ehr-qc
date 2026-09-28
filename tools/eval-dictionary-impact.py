"""词典影响离线评估：用库里 structured_data 的「原文」，模拟三级判定，比较新旧词典的命中率。

为什么要它：词典一改，归一结果与质控分就跟着变，而跑一次全库重算代价很高。
本脚本在**不碰 ES、不碰库**的前提下（只读 structured_data），用与
`EsTermNormalizer.judge` 逐字一致的规则算出「改词典前 / 后」的命中率，
用来判断这次词典改动是否值得写盘。

已知偏差（须知悉）：真实链路先经 ES 召回（`RECALL_SIZE=50`，模糊查询），
本脚本对**全部词条**判定 → 结果是**命中率上限**，比线上略乐观。

用法：
    python tools/eval-dictionary-impact.py
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
    """从 MySQL 读 structured_data，抽出每类的原文（sourceText）与线上是否命中"""
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
    for raw in rows:
        try:
            data = json.loads(raw)
        except Exception:
            continue
        for field in FIELD_TO_FILE:
            for ent in (data.get(field) or []):
                if not isinstance(ent, dict):
                    continue
                term = (ent.get(NAME_FIELD.get(field, 'content')) or ent.get('sourceText') or '').strip()
                if term:
                    corpus[field].append((term, ent.get('normLevel')))
    return corpus


def main():
    ap = argparse.ArgumentParser(description='词典影响离线评估（只读 structured_data，不碰 ES）')
    ap.add_argument('--old-dir', default=DICT_DIR, help='旧词典目录（默认 data/dictionaries）')
    ap.add_argument('--new-dir', required=True, help='新词典目录（含同名 JSON）')
    ap.add_argument('--limit', type=int, default=12, help='可疑样本打印条数')
    args = ap.parse_args()

    old, new = load_dicts(args.old_dir), load_dicts(args.new_dir)
    corpus = load_corpus()

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
