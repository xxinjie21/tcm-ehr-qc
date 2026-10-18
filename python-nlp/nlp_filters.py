"""抽取产物的产物级过滤（不依赖模型、不依赖词典，纯形态与语境判据）。

为什么放在 Python 侧而不是 Java 侧：这些 span 是 NER 的**原始输出**，越早剔除，
后面的归一、扣分、导出替换全都少背一份噪声。Java 侧的 `dropSingleCharFragments`
只认识「词典里有没有」，对「细胞角蛋白19」这种不在任何词典里的鬼词无能为力。

五条判据各自独立可测：
    F1 单字残词    len(span) < 2 的无词典字段
    F2 假药名      中药名的形态合理性（全汉字 / 长度 / 机构语素 / 省份开头）
    F3 否定句阳性  span 自带否定词，或同段前文里存在否定辖域
    F4 否定泄漏    **任意实体类**（含疾病）被否定辖域覆盖时剔除 —— 2026-10-18 实测「无脚气」
                   仍被抽成疾病名，只因 F3 此前只作用于症状列
    F5 症状噪声    含阿拉伯数字（「反复发作5」「经前3天乳房胀」）、以「为主」收尾（「头面为主」）、
                   纯常态陈述（「小便可」「纳可」）、描述性碎片（「反复发作」）
"""
import re
from typing import Dict, List, Optional

from schemas import ExtractResponse

# ---- F1 单字 ------------------------------------------------------------------
# 只对无独立词典的字段（病因/治法）与长度判据；舌/脉/症状/疾病/中药/方剂的单字
# 交给 Java 侧按「是否精确命中词典」处理（词典里确有单字标准词，一刀切会误删）。
_SINGLE_CHAR_FIELDS = ("symptoms", "diseases", "patternList", "causeList", "treatmentList")

# ---- F2 中药形态 --------------------------------------------------------------
# 机构/检验语素：出现即不是药名（都是实测把院名、化验项抽成中药的元凶）
HERB_STOP = ("蛋白", "细胞", "抗原", "抗体", "片段", "细菌", "病毒", "基因", "激素",
             "免疫", "检验", "检查", "结果", "报告", "医院", "大学", "学院", "研究院",
             "研究所", "中心", "妇保", "门诊", "科内", "系列")
# 省份简称：`江西省` / `江西中`（后者是前者的截断）都以它开头
PROVINCE_HEADS = ("北京", "上海", "天津", "重庆", "河北", "山西", "辽宁", "吉林", "黑龙江",
                  "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南",
                  "广东", "广西", "海南", "四川", "贵州", "云南", "陕西", "甘肃", "青海",
                  "内蒙古", "新疆", "西藏", "宁夏", "香港", "澳门", "台湾")
HERB_MIN_LEN, HERB_MAX_LEN = 2, 5


def _all_cjk(s: str) -> bool:
    return bool(s) and all("\u4e00" <= ch <= "\u9fff" for ch in s)


def is_plausible_herb(span: str) -> bool:
    """中药名的形态合理性：全汉字、2~5 字、无机构/检验语素、不以省份开头。"""
    span = (span or "").strip()
    if not (_all_cjk(span) and HERB_MIN_LEN <= len(span) <= HERB_MAX_LEN):
        return False
    if any(w in span for w in HERB_STOP):
        return False
    if span.startswith(PROVINCE_HEADS):
        return False
    return True


# ---- F3 否定 ------------------------------------------------------------------
# ⚠️ 判定集合刻意**不含** 欠/缺/忌/禁/寡：「睡眠欠佳」「纳欠佳」「忌口」是规范写法，
# 词自身就带这些字，收进来会把正常症状当否定句误删（实测「睡眠欠佳」会被删）。
NEG_HARD = ("无", "未", "莫", "非", "否认", "未见", "无明显", "排除")
# 「不X」里 X 属于这些语素时，是**规范说法**（症状名本身带「不」），不能当否定句
NEG_KEEP_TAIL = ("寐", "适", "安", "宁", "固", "化", "和", "利", "扬", "仁", "遂", "孕",
                 "消", "佳", "畅", "眠", "食", "欲", "得", "解", "知", "足", "均", "能",
                 "受", "育", "明", "振", "规", "思", "运", "痛", "调")
# 回看的上限（防超长文本 O(n²)）。真正决定「看到哪」的是**分句界**，不是这个长度。
NEG_WINDOW = 30
# 回看时遇到这些**分句界**就停：否定词不跨分句作用。
# ⚠️ 刻意**不**把顿号「、」当界 —— 「无 A、B、C」里 A/B/C 同属一个否定辖域，
# 把顿号当界会让枚举里靠后的项（打嗝）逃过否定判定。
NEG_BOUNDARY = "。；;！？，,:：\n"


def _has_normative_bu(span: str) -> bool:
    """span 里的「不」是否属于规范说法（食欲不振 / 大便不畅 / 不寐）。"""
    for i, ch in enumerate(span):
        if ch == "不" and i + 1 < len(span) and span[i + 1] in NEG_KEEP_TAIL:
            return True
    return False


def _span_self_negated(span: str) -> bool:
    """span 自身带否定词（规范「不X」除外）。"""
    if any(w in span for w in NEG_HARD):
        return True
    return "不" in span and not _has_normative_bu(span)


def _negated_before(text: str, pos: int) -> bool:
    """pos 之前、**同一分句内**是否有否定词（顿号不隔断辖域；分句界截断）。"""
    ctx = text[max(0, pos - NEG_WINDOW):pos]
    cut = -1
    for i in range(len(ctx) - 1, -1, -1):
        if ctx[i] in NEG_BOUNDARY:
            cut = i
            break
    if cut >= 0:
        ctx = ctx[cut + 1:]
    return any(w in ctx for w in NEG_HARD) or ("不" in ctx and not _has_normative_bu(ctx))


def _all_positions(text: str, span: str) -> List[int]:
    """span 在 text 里出现的全部位置（用于「所有出现都须是否定才算否定」）。"""
    out: List[int] = []
    i = text.find(span)
    while i >= 0:
        out.append(i)
        i = text.find(span, i + 1)
    return out


def is_negated_span(span: str, text: str = "", start: Optional[int] = None,
                    end: Optional[int] = None) -> bool:
    """该 span 是否处于否定语境。

    ① span 自带否定词（非规范「不X」）→ 是；
    ② 给了 (text, start) → 只看该处；
       只给 text → 看**全部出现**，要求「每一处都在否定语境」才算否定
       —— 同一个词若既有肯定用法又有否定用法，不能整条删掉。
    """
    span = (span or "").strip()
    if not span:
        return False
    if _span_self_negated(span):
        return True
    if not text:
        return False
    if start is not None:
        return _negated_before(text, start)
    positions = _all_positions(text, span)
    if not positions:
        return False
    return all(_negated_before(text, p) for p in positions)


# ---- 统一入口 -----------------------------------------------------------------
# F4 否定泄漏的作用范围：**所有**实体类（不只是症状）。舌/脉/治法同样会被「无/不」否定，
# 疾病尤其常见（「排除肿瘤」「无高血压」）。全出现都在否定语境才丢（见 is_negated_span）。
_ENTITY_FIELDS = ("diseases", "symptoms", "patternList", "formulaList",
                  "causeList", "treatmentList", "tongueList", "pulseList")
# F5 症状形态噪声判据
_DIGIT_RE = re.compile(r"\d")
# 纯「常态陈述」：小便可 / 纳可 / 眠可 / 二便可 —— 是「正常」而非症状，进词表只会瞎命中
_NORMAL_STATE_RE = re.compile(r"^[\u4e00-\u9fff]{1,3}(可|正常)$")
# 描述性连接碎片：模型常把主诉里的「反复发作」整段当症状
_NOISE_EXACT = {"反复发作", "反复出现", "无明显异常", "无特殊"}


def _is_noisy_symptom(s: str) -> bool:
    """非症状的抽取碎片：含阿拉伯数字（「反复发作5」「经前3天乳房胀痛」）、
    以「为主」收尾（「头面为主」）、纯常态陈述（「小便可」「纳可」）、或描述性碎片（「反复发作」）。"""
    return (bool(_DIGIT_RE.search(s)) or s.endswith("为主")
            or s in _NOISE_EXACT or bool(_NORMAL_STATE_RE.match(s)))


def drop_noise(resp: ExtractResponse, full_text: str = "") -> Dict[str, int]:
    """就地过滤抽取结果，返回各字段丢弃条数（供日志/报表对账）。

    `full_text` 给了才做「窗口否定」判定（由 is_negated_span 自行定位 span 出现位置）；
    只给 span 的调用点仍走「span 自带否定」这一半。
    """
    counts: Dict[str, int] = {}
    # F1 单字残词
    for field in _SINGLE_CHAR_FIELDS:
        items: List = getattr(resp, field) or []
        kept = [e for e in items if len((e.content or "").strip()) >= 2]
        counts[field] = len(items) - len(kept)
        setattr(resp, field, kept)
    # F3+F4 否定：所有实体类一起过（含疾病）；传全文，函数内部定位每一处出现
    for field in _ENTITY_FIELDS:
        items: List = getattr(resp, field) or []
        kept = [e for e in items
                if not is_negated_span(e.content or e.sourceText or "", full_text)]
        counts[field] = counts.get(field, 0) + (len(items) - len(kept))
        setattr(resp, field, kept)
    # F5 症状形态噪声
    symptoms = resp.symptoms or []
    kept_s = [e for e in symptoms if not _is_noisy_symptom(e.content or e.sourceText or "")]
    counts["symptoms"] = counts.get("symptoms", 0) + (len(symptoms) - len(kept_s))
    resp.symptoms = kept_s
    # F2 中药形态过滤（丢了的连着 dosage 一起丢）
    herbs = resp.herbs or []
    kept_h = [h for h in herbs if is_plausible_herb(h.name or h.sourceText or "")]
    counts["herbs"] = len(herbs) - len(kept_h)
    resp.herbs = kept_h
    return {k: v for k, v in counts.items() if v}
