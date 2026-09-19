"""国标 PDF → 术语 JSON 离线转换（批A·1.4 的断网兜底路径）

用途：老师提供真实国标 PDF 时，用本脚本抽表格出 5 类 JSON，产物可直接走
      「术语词典 → 导入」的 JSON 直传路径入库，不依赖 LLM 与网络。

依赖：pdfplumber（需自行安装）
    pip install pdfplumber

用法：
    python tools/convert-standard-pdf.py <pdf路径> --type symptom
    python tools/convert-standard-pdf.py <pdf路径> --type herb --dry-run
    python tools/convert-standard-pdf.py <pdf路径> --type pattern --out output.json \
        --source "中医病证分类与代码 GB/T 15657-2021"

说明：
    - 优先按 PDF 表格解析（第 1 列=标准术语，第 2 列=别名）；
      若整份 PDF 抽不到表格，退化为按文本行解析（制表符/多空格/顿号分隔）。
    - 默认只打印校对清单，加 --write 才真正覆盖词库文件（避免误覆盖）。
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT_DIR = os.path.join(ROOT, 'data', 'dictionaries')

TYPE_TO_FILE = {
    'disease': 'diseases.json',
    'pattern': 'patterns.json',
    'symptom': 'symptoms.json',
    'herb': 'herbs.json',
    'formula': 'formulas.json',
}

DEFAULT_SOURCE = {
    'disease': '中医临床诊疗术语 疾病',
    'pattern': '中医病证分类与代码 GB/T 15657-2021',
    'symptom': '中医临床诊疗术语 症状',
    'herb': '中国药典2025年版',
    'formula': '中医方剂大辞典',
}

# 别名的分隔符：顿号/逗号/分号/竖线/空格（与后端 import 的切分口径一致）
ALIAS_SPLIT = re.compile(r'[、,，;；|/\s]+')
HEADER_HINTS = ('标准术语', '术语', '别名', '异名', 'standardterm', '别名/异名')


def die(msg):
    print('[错误] ' + msg, file=sys.stderr)
    sys.exit(1)


def load_pdfplumber():
    try:
        import pdfplumber  # noqa: F401
    except ImportError:
        die('缺少依赖 pdfplumber，请先安装：pip install pdfplumber')
    return pdfplumber


def is_header(cells):
    joined = ' '.join(c or '' for c in cells).lower()
    return any(h in joined for h in HEADER_HINTS)


def clean(text):
    if text is None:
        return ''
    return re.sub(r'\s+', ' ', str(text)).strip()


def split_aliases(raw, standard):
    out = []
    for a in ALIAS_SPLIT.split(clean(raw)):
        a = a.strip()
        if a and a != standard and a not in out:
            out.append(a)
    return out


def from_tables(pdf):
    """按表格解析，返回 (术语映射, 抽到的表格数)"""
    found = {}
    table_count = 0
    for page in pdf.pages:
        for table in page.extract_tables() or []:
            table_count += 1
            for row in table:
                if not row:
                    continue
                cells = [clean(c) for c in row]
                if is_header(cells) or not cells[0]:
                    continue
                standard = cells[0]
                # 有些国标表格第 2 列是代码、第 3 列才是别名，这里取「最像别名的那一列」
                alias_col = cells[1] if len(cells) > 1 else ''
                for extra in cells[2:]:
                    if extra and ALIAS_SPLIT.search(extra):
                        alias_col = alias_col + '、' + extra
                found.setdefault(standard, [])
                for a in split_aliases(alias_col, standard):
                    if a not in found[standard]:
                        found[standard].append(a)
    return found, table_count


def from_lines(pdf):
    """按文本行解析（无表格 PDF 的兜底）"""
    found = {}
    for page in pdf.pages:
        for line in (page.extract_text() or '').split('\n'):
            line = clean(line)
            if not line or is_header((line,)):
                continue
            parts = [p for p in re.split(r'\t| {2,}', line) if p.strip()]
            if not parts:
                continue
            standard = clean(parts[0])
            if not standard or len(standard) > 30:
                continue
            found.setdefault(standard, [])
            if len(parts) > 1:
                for a in split_aliases(parts[1], standard):
                    if a not in found[standard]:
                        found[standard].append(a)
    return found


def main():
    ap = argparse.ArgumentParser(description='国标 PDF → 术语 JSON（离线，不依赖 LLM）')
    ap.add_argument('pdf', help='国标 PDF 路径')
    ap.add_argument('--type', required=True, choices=sorted(TYPE_TO_FILE),
                    help='目标词典类型')
    ap.add_argument('--out', help='输出 JSON 路径（默认 data/dictionaries/<对应文件>）')
    ap.add_argument('--source', help='来源标注（默认按类型取国标名）')
    ap.add_argument('--write', action='store_true',
                    help='真正写文件；不加则只打印校对清单')
    ap.add_argument('--limit', type=int, default=10, help='清单中预览条数，默认 10')
    args = ap.parse_args()

    if not os.path.isfile(args.pdf):
        die('找不到 PDF：' + args.pdf)

    pdfplumber = load_pdfplumber()
    source = args.source or DEFAULT_SOURCE[args.type]
    out_path = args.out or os.path.join(DICT_DIR, TYPE_TO_FILE[args.type])

    with pdfplumber.open(args.pdf) as pdf:
        page_count = len(pdf.pages)
        found, table_count = from_tables(pdf)
        mode = '表格'
        if not found:
            found = from_lines(pdf)
            mode = '文本行'

    if not found:
        die('未从 PDF 中解析出任何术语（可能是图片型 PDF，需先 OCR）')

    # 组 TermEntry（与后端 import 的 JSON 契约一致：standardTerm / aliases[] / source / code）
    entries = []
    suspicious = []
    for standard, aliases in found.items():
        entries.append({
            'standardTerm': standard,
            'aliases': aliases,
            'source': source,
        })
        if len(standard) > 20:
            suspicious.append(f'{standard}（标准术语过长，疑似把整行当成了术语）')
        elif len(standard) <= 1:
            suspicious.append(f'{standard}（标准术语仅 1 字，请核对）')

    print('=' * 62)
    print(f'PDF        : {args.pdf}')
    print(f'页数       : {page_count}')
    print(f'解析方式   : {mode}（抽到 {table_count} 个表格）')
    print(f'目标词典   : {args.type} -> {TYPE_TO_FILE[args.type]}')
    print(f'来源标注   : {source}')
    print(f'术语条数   : {len(entries)}')
    print(f'含别名条数 : {sum(1 for e in entries if e["aliases"])}')
    print('-' * 62)
    print(f'校对清单（前 {args.limit} 条）：')
    for e in entries[:args.limit]:
        al = ('、'.join(e['aliases'])) or '—'
        print(f'  {e["standardTerm"]:<14} 别名：{al}')
    if suspicious:
        print('-' * 62)
        print(f'可疑条目（{len(suspicious)} 条，建议人工核对）：')
        for s in suspicious[:args.limit]:
            print('  ! ' + s)
    print('=' * 62)

    if not args.write:
        print(f'\n[预览模式] 未写文件。确认无误后加 --write 写到：{out_path}')
        return

    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(entries, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(f'\n[已写入] {out_path}')
    print('下一步：到「术语词典」页用 JSON 直传导入，或直接 POST /api/dictionary/import。')


if __name__ == '__main__':
    main()
