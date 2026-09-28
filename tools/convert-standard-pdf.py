"""国标 PDF / DOCX → 术语 JSON 离线转换（批A·1.4 的断网兜底路径）

用途：拿到真实国标（PDF 或 DOCX）时，用本脚本出 5 类 JSON，产物可直接走
      「术语词典 → 导入」的 JSON 直传路径入库，不依赖 LLM 与网络。

依赖：PDF 需 pdfplumber（`pip install pdfplumber`）；DOCX 只需标准库 zipfile。

用法：
    python tools/convert-standard-pdf.py <PDF或DOCX路径> --type symptom
    python tools/convert-standard-pdf.py <DOCX路径> --type disease --dry-run
    python tools/convert-standard-pdf.py <DOCX路径> --type symptom --section 18
    python tools/convert-standard-pdf.py <PDF路径> --type pattern --out output.json \
        --source "中医病证分类与代码 GB/T 15657-2021"

说明：
    - PDF：优先按表格解析（第 1 列=标准术语，第 2 列=别名）；整份抽不到表格则退化为
      按文本行解析（制表符/多空格/顿号分隔）。
    - DOCX：直接读 word/document.xml，按国标条目体例解析 ——
      「编号 → 标准名（可带英文） → 别名（0~N 条） → 定义 → 注：…」。
      docx 是结构化 XML，比 PDF 抽表稳，别名也能稳定取到。
    - --section N：只取编号以「N.」开头的条目。用于从《中医临床诊疗术语 第1部分：疾病》
      里单独抽出第 18 章「临时诊断用术语（症状性术语）」当症状词表。
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

# ---------------------------------------------------------------- DOCX 解析
# 国标条目体例：编号独占一段（如 2.1 / 2.1.1 / 18.9），标准名与英文同一段
NUM_RE = re.compile(r'^\d+(?:\.\d+)+\.?$')
# 定义段的起头词：命中即说明「别名块」结束
DEF_PREFIX = ('因', '临床以', '泛指', '指', '多因', '由于', '凡因')
# 别名长度上限：国标别名都是短术语，超过这个长度基本是定义句
MAX_ALIAS_LEN = 15


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


# ---------------------------------------------------------------- DOCX 解析

def docx_paragraphs(path):
    """读 docx 正文段落（无需第三方库：word/document.xml 就是 XML）

    <w:p> 是段落边界，必须先把开标签换成换行再剥标签，否则整篇会被压成一行。
    """
    import zipfile
    with zipfile.ZipFile(path) as z:
        xml = z.read('word/document.xml').decode('utf-8')
    xml = re.sub(r'<w:p[ >]', '\n<w:p ', xml)
    text = re.sub(r'<[^>]+>', '', xml)
    return [ln.strip() for ln in text.split('\n') if ln.strip()]


def strip_english(line):
    """切掉标准名后面的英文对应词：「绦虫病  tapeworm disease」→「绦虫病」"""
    m = re.match(r'^([^A-Za-z]+?)\s*[A-Za-z]', line)
    return (m.group(1) if m else line).strip()


def from_docx(path, section=None):
    """按国标条目体例解析 docx，返回 {标准术语: [别名…]}

    体例（实测《中医临床诊疗术语》第 1／2 部分）：
        3.2                              ← 编号独占一段
        绦虫病  tapeworm disease         ← 标准名 + 英文
        小儿绦虫病                        ← 别名（0~N 条，都是短术语）
        寸白虫病
        因吞食含有绦虫幼虫…              ← 定义（起头词见 DEF_PREFIX）
        注： 常见于…                     ← 注释，忽略

    section：只取编号以「<section>.」开头的条目（用于单独抽第 18 章症状性术语）。
    """
    paras = docx_paragraphs(path)
    found = {}
    i = 0
    while i < len(paras):
        # 1. 只在「编号段」处开一条新条目，其余段落一律跳过
        if not NUM_RE.match(paras[i]):
            i += 1
            continue
        num = paras[i]
        i += 1
        # 2. 编号后必须是标准名段；越界或又是编号说明是孤立编号，丢弃
        if i >= len(paras) or NUM_RE.match(paras[i]):
            continue
        standard = strip_english(paras[i])
        i += 1
        # 3. 收别名：短、纯中文、非定义起头；遇到编号/注/英文/长句即停
        aliases = []
        while i < len(paras):
            line = paras[i]
            if (NUM_RE.match(line) or line.startswith('注')
                    or len(line) > MAX_ALIAS_LEN
                    or line.startswith(DEF_PREFIX)
                    or re.search(r'[A-Za-z]', line)):
                break
            if line and line != standard and line not in aliases:
                aliases.append(line)
            i += 1
        # 4. 按 section 过滤（如只要 18.x 的症状性术语）
        if section and not num.startswith(section + '.'):
            continue
        if standard:
            found.setdefault(standard, [])
            for a in aliases:
                if a not in found[standard]:
                    found[standard].append(a)
    return found


def main():
    ap = argparse.ArgumentParser(description='国标 PDF / DOCX → 术语 JSON（离线，不依赖 LLM）')
    ap.add_argument('source_path', help='国标 PDF 或 DOCX 路径')
    ap.add_argument('--type', required=True, choices=sorted(TYPE_TO_FILE),
                    help='目标词典类型')
    ap.add_argument('--out', help='输出 JSON 路径（默认 data/dictionaries/<对应文件>）')
    ap.add_argument('--source', help='来源标注（默认按类型取国标名）')
    ap.add_argument('--section', help='DOCX 专用：只取编号以「N.」开头的条目（如 18 = 症状性术语）')
    ap.add_argument('--write', action='store_true',
                    help='真正写文件；不加则只打印校对清单')
    ap.add_argument('--limit', type=int, default=10, help='清单中预览条数，默认 10')
    args = ap.parse_args()

    path = args.source_path
    if not os.path.isfile(path):
        die('找不到文件：' + path)

    source = args.source or DEFAULT_SOURCE[args.type]
    out_path = args.out or os.path.join(DICT_DIR, TYPE_TO_FILE[args.type])
    page_count = 0
    table_count = 0

    # 1. 按扩展名分派：docx 走 XML 条目体例，其余按 PDF 处理
    if path.lower().endswith('.docx'):
        found = from_docx(path, section=args.section)
        mode = 'DOCX·条目体例' + (f'（限第 {args.section} 章）' if args.section else '')
    else:
        pdfplumber = load_pdfplumber()
        with pdfplumber.open(path) as pdf:
            page_count = len(pdf.pages)
            found, table_count = from_tables(pdf)
            mode = 'PDF·表格'
            if not found:
                found = from_lines(pdf)
                mode = 'PDF·文本行'

    if not found:
        die('未解析出任何术语（PDF 可能是图片型需先 OCR；docx 请核对是否为国标条目体例）')

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

    # 2. 丢掉「与其它条目的标准词撞车」的别名。一级精确按列表顺序取首个命中，
    #    撞车会让结果取决于词条顺序（脆弱，且国标里类目词会把成员词当别名）；
    #    去掉后同一输入只有唯一解。
    standards = {e['standardTerm'] for e in entries}
    dropped = 0
    for e in entries:
        kept = [a for a in e['aliases'] if a not in standards]
        dropped += len(e['aliases']) - len(kept)
        e['aliases'] = kept

    print('=' * 62)
    print(f'源文件     : {path}')
    if page_count:
        print(f'页数       : {page_count}')
    print(f'解析方式   : {mode}' + (f'（抽到 {table_count} 个表格）' if table_count else ''))
    print(f'目标词典   : {args.type} -> {TYPE_TO_FILE[args.type]}')
    print(f'来源标注   : {source}')
    print(f'术语条数   : {len(entries)}')
    print(f'含别名条数 : {sum(1 for e in entries if e["aliases"])}')
    if dropped:
        print(f'丢弃别名   : {dropped} 条（与其它条目的标准词撞车，会造成顺序依赖）')
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
