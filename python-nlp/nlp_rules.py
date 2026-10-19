"""NLP 规则兜底与舌脉要素归位（纯规则，不 import torch）。

只依赖 schemas。三层里的中间层：正则抽取舌象 / 脉象 / 治法 / 病因，
以及把误落进 symptoms 的脉舌要素移回 pulseList / tongueList。
"""
import re
from typing import Dict, List

from nlp_filters import is_plausible_herb
from schemas import Entity, ExtractResponse, Herb

# 舌象 / 脉象：批次 20 起已有 tongues.json / pulses.json 词典（此前只有格式规则）。
# 下面的正则仍在词典归一之前把子要素切成独立段（舌质/舌苔/齿痕/裂纹），
# 抽不到或词表未收录的段保留原文，出厂默认不计入未归一扣分
# （默认哪几类计分见 QcRuleSet.defaultStandardization；可在「规则配置」里勾选调整）。
# 换数据集若写「左关尤甚」（无「脉」字）或苔色单独成列，仍会漏。见 §九 9.5 风险 1/2。
#
# ①  舌：字符类由「舌」扩到「舌苔」。原正则只认「舌」起头，`舌质淡红，苔薄白` 会把
#     「苔薄白」整段丢掉（苔色、苔质是舌诊的另一半）。
# ②  舌：补**形态锚**（批次 20）。`边有齿痕`/`有裂纹`/`有瘀点` 不以「舌/苔」起头，
#     原正则整段丢掉 —— 本数据集 500 条里 50 条含「边有齿痕」，全部丢失。
#     这几类是舌形/舌面的标准子要素，丢掉等于该子要素完全不可分析。
#     只认这几个固定形态词，不泛化到任意「有X」——
#     否则「有神」「有神气」一类描述性措辞会被误当成子要素。
#     尾部的字符组用 * 而不是 +：`有裂纹` 本身只有 3 字，尾部可能为空，
#     用 {1,6} 会要求它后面还必须有字，反而漏掉最常见的写法。
#     「或」连接也收（原文是「舌红少苔，或有裂纹」），输出时去掉这个连词。
# ①b 苔后加负向先行 `(?!藓)`：皮肤病名以「苔藓」打头（苔藓 / 苔藓样皮疹 / 苔藓样皮损），
#     与舌苔同首字却完全不是舌诊内容。2026-10-15 实测全库舌象有 44745 条命中本条正则，
#     其中皮肤病名 9 条 —— 只差这一个 lookahead 就能挡在外面，且零误伤
#     （逐个词比对：新正则相对旧正则排除的恰好是 苔藓样* 4 个词 / 9 条）。
RULE_TONGUE = re.compile(r"^[舌苔](?!藓)[^，。、；;\s]{1,8}$")
# ②b 舌：任务 4 再补 4 个形态锚（有芒刺 / 剥苔 / 无苔 / 少苔）。
#     `有芒刺` 与 `有瘀` 并列而不是合并成 `有` —— 见上，泛化到任意「有X」会误伤。
#     `剥苔 / 无苔 / 少苔` 不以「舌」「苔」起头，RULE_TONGUE 的 `^[舌苔]` 覆盖不到；
#     而 `薄苔 / 厚苔 / 苔腻 / 苔滑` 首字是「苔」，RULE_TONGUE 已经管了，
#     **不要**重复登记 —— 重复的锚在正则里是死分支，只会让人误以为它有作用。
#     ⚠️ 这 4 个锚在演示数据 500 行里命中 0 次（那批数据的苔质都写成「苔薄白」
#     「苔白腻」这类以苔起头的段），所以**收益无实测支撑**，目前只有
#     tests/test_batch20_rules.py 的用例在钉住它们的行为。
#     注意：**不要**把 `边有` 收窄成 `边有齿痕`。收窄后 `边有瘀斑` 一类会整段丢掉，
#     而演示数据恰好只写「边有齿痕」，收窄造成的损失在那份数据上看不出来（隐蔽回归）。
RULE_TONGUE_MORPH = re.compile(r"(?:或)?(?:边有|有裂纹|有瘀点|有瘀|有芒刺|剥苔|无苔|少苔)[^，。、；;\s]{0,6}")
# ③  脉：脉位必须**独立成段**（批次 20）。原正则把「，左尺无力」作为可选续接吞进
#     前一段，得到 `脉细数，左尺无力` 一条 —— 脉位（左/右 + 寸关尺）是独立于脉象的
#     标准维度，混在一起就分不出「脉细数」与「左尺无力」两个不同语义。
#     仍只认「左/右 + 寸关尺」这几个固定锚，不照搬「任意逗号续接」——
#     否则 `脉细，舌红` 的「舌红」会被吞进脉象。
RULE_PULSE = re.compile(r"^脉[^，。、；;\s]{1,6}$")
RULE_PULSE_POSITION = re.compile(r"(?:左|右)(?:寸|关|尺)[^，。、；;\s]{0,4}")
# ⚠️ 上面这条**不要**收窄。`docs/NLP服务性能与准确率提升计划.md` 的任务 4 曾提议改成
#    `(?:左|右)(?:寸|关|尺)(?:浮|沉|迟|数|虚|实|滑|涩|洪|细|弦|紧|缓|弱|微|濡)[^…]{0,4}`，
#    即要求脉位后紧跟一个脉质字。该写法**是回归**：演示数据里「左尺无力」「左关尤甚」
#    的后缀是「无力」「尤甚」，两个词都不在那 16 个字里，实测脉象会从 144 条次掉到 120
#    条次（-16.7%）。本正则允许任意非标点后缀，正是为了收住这类非脉质描述。
# 按标点分段的统一切分：舌象/脉象的子要素判定依赖段边界（见 _rules 注释）
SEG_SPLIT = re.compile(r"[，。、；;\s]+")
# ⚠️ 不要再补 `法宜` / `当予` 一类的引导词（2026-10-17 加过又撤回）。
#    撤回理由与病因表那次同源：**在词典里找不到落点**。
#      · `treatments.json` 的 1186 条里没有任何条目以 `法宜` / `当予` 为 standardTerm 或 alias；
#      · 抽出来的段是「法宜健脾益气」这种**带引导词的整段**，拿它去归一大概率命中不到
#        `健脾益气` 本身（本正则原本就有这个问题，不是新增的，但没必要扩大）。
#      · `当予` 另有明确误报路径：「应当予以复查」会抽出「当予以复查」当成治法。
#      · 演示数据 500 行里这两个词命中 0 次（整批数据连「治」字都没有，treatmentList 恒为空），
#        即**无任何数据能验证它们**。
#    判据同病因表：加之前先 grep `dictionaries/`，词典里没有对应条目就不要加。
# ⚠️ 只认带引导词的治法（治以 / 治法 / 治则 / 治宜）。原先的 `治[以法]?[^，。；;\s]{1,10}`
#    会把「甲硝锉消炎治疗」「光动力治疗5次」「治疗过程中自觉烧灼感」整段收成治法
#    —— 2026-10-18 实测一条真实病历就产出 3 条这样的噪声。
#    本数据集里治法本就恒空，收窄不会丢真实治法；要放宽必须先有一批带「治以…」的真实数据。
RULE_TREATMENT = re.compile(r"治[以法则宜][^，。；;\s]{1,10}")
# ⚠️ 本表**不要再扩**「证候 / 病名」性质的词（2026-10-17 撤回过一次，别再加回来）。
#    教训：任务 4 曾把 肝郁 / 脾虚 / 肾虚 / 火热 / 水饮 / 食积 6 个词加进本表，
#    后来撤回。原因是这 6 个词在词典里的身份根本不是「病因」：
#      · `肝郁` 是 `patterns.json` 里 `肝气郁结` 的**别名**（另有 `肝郁气滞` 独立条目）；
#      · `食积` 是 `diseases.json` 的**病名**；
#      · `脾虚`(31 条) / `肾虚`(23 条) / `火热`(7 条) / `水饮`(1 条) 只作为
#        `patterns.json` 里证候名的组成部分出现（`脾虚证` / `肾虚证` / `火热证` / `水饮内停证`）。
#    它们本就该由证候/病名两路归一收走（那两条有词典），再当病因抽一遍是**跨类重复计数**。
#    而 `cause` 在 EntityTypes 里是 `dict=false`（无词典、不归一），也印证它不是给这类词准备的。
#    判据：加词前先 `grep` 一遍 `java-backend/src/main/resources/dictionaries/` ——
#    若该词已是某条目的 standardTerm 或 alias，就说明它属于那个类，不要往本表加。
RULE_CAUSE_WORDS = ["风寒", "风热", "暑湿", "风湿", "湿热", "寒湿", "气虚", "血虚", "阴虚", "阳虚",
                    "情志", "饮食", "劳倦", "外伤", "痰", "瘀", "外感"]
# 注：不要加「湿热蕴结」这类含更短词的条目 —— 命中判定是 `w in text`，
# 文本里有「湿热蕴结」时「湿热」已经命中，再加一条会让同一个病因出现两次
DOSAGE_RE = re.compile(r"(\d+(?:\.\d+)?)\s*(g|ml|mg|片|枚|条|张|付)")
# 处方吃药名兜底（批次 21，2026-10-18）：模型 NER 会漏掉处方里部分药名
# （实测一条真实病历漏了 岷当归 / 白芍 / 泽泻 三味）。形态「分隔符 + 药名 + 剂量」是处方里
# 最稳的写法，用它兜底。**只认 g / 克**：片/付/条 等是剂型/付数，不是单味药剂量，
# 一并收进来会把「中草药处1(15付)」误当药名。要求整段名是 `is_plausible_herb` 才收。
HERB_DOSE_RE = re.compile(
    r"(?:^|[，,、;；。\s（(])([\u4e00-\u9fff]{2,6})\s*(\d+(?:\.\d+)?\s*(?:g|克))")


def _rules(text: str) -> Dict[str, List[Entity]]:
    """规则兜底：正则抽舌象 / 脉象 / 治法，病因表按包含匹配；始终基于全文。"""
    def ent(s):
        return Entity(content=s, sourceText=s, source="rule")

    # 1. 舌象 / 脉象 / 治法走正则：按标点分段逐段判定。
    #    分段而不是整段 finditer 的原因：形态词「或」在原文里自带连接作用
    #    （「舌红少苔，或有裂纹」），只有按段判定才知道它是一段独立描述；
    #    而脉位要独立成段，也依赖段边界。
    tongue = []
    for seg in SEG_SPLIT.split(text):
        if not seg:
            continue
        if RULE_TONGUE.match(seg) or RULE_TONGUE_MORPH.match(seg):
            # 输出时去掉「或」连接词：它是原文的连词，不是子要素的一部分
            tongue.append(ent(seg[1:] if seg.startswith("或") else seg))
        # 无「舌/苔」前缀的舌诊专名（边/尖/根/齿痕 打头，全汉字，≤8 字）
        elif _seg_cjk(seg) and len(seg) <= 8 and seg.startswith(_TONGUE_HEADS_EX) \
                and not seg.startswith(_PT_BLACKLIST):
            tongue.append(ent(seg))
    # 2. 脉象：脉象本体与脉位分开成段（批次 20）。
    #    脉位（左/右 + 寸关尺）是独立于脉象的标准维度，合在一段就分不出
    #    「脉细数」与「左尺无力」两个语义。按标点分段后天然独立，无需额外剥离。
    pulse = [ent(seg) for seg in SEG_SPLIT.split(text)
             if seg and RULE_PULSE.match(seg)]
    # 无「左/右」前缀的脉位（寸/关/尺 打头，全汉字，2~6 字），如「寸略旺」「尺较沉」
    pulse.extend(ent(seg) for seg in SEG_SPLIT.split(text)
                 if _seg_cjk(seg) and 2 <= len(seg) <= 6
                 and seg.startswith(_PULSE_HEADS) and not seg.startswith(_PT_BLACKLIST)
                 and seg not in [p.content for p in pulse])
    pulse.extend(ent(m.group(0)) for m in RULE_PULSE_POSITION.finditer(text)
                 if m.group(0) not in [p.content for p in pulse])
    # 脉位排在脉象之后：阅读顺序是「先读脉象，再看哪一部位异常」
    # 2. 病因走词表包含匹配；dict.fromkeys 的去重作用对此处是冗余的（RULE_CAUSE_WORDS 本身无重复项），只保证顺序与控制去重即可
    cause = [ent(w) for w in dict.fromkeys(RULE_CAUSE_WORDS) if w in text]
    treatment = [ent(m.group(0)) for m in RULE_TREATMENT.finditer(text)]
    # 3. 处方吃药名兜底：模型漏抽的药名在这里按「药名+剂量」补回（重复项在 Java 侧按术语去重）
    herbs, seen = [], set()
    for m in HERB_DOSE_RE.finditer(text):
        name = m.group(1)
        if name in seen or not is_plausible_herb(name):
            continue
        seen.add(name)
        herbs.append(Herb(name=name, dosage=m.group(2), sourceText=name, source="rule"))
    return {"tongueList": tongue, "pulseList": pulse, "causeList": cause,
            "treatmentList": treatment, "herbs": herbs}


# ---- 舌脉要素判据（与 Java 侧 StandardizationReportServiceImpl.MISROUTED_PREFIX
#      + tools/reroute-pulse-tongue-stored.py 三处同口径：同一件事不能各写一套）----
# 模型没有脉象/舌象标签，这两类要素会落进 symptoms。带「脉」「舌」前缀的自不必说，
# 还有一批**书写时省略了前缀**的：
#   脉诊三部 —— 尺/寸/关 打头（尺沉、寸浮、关缓）。三部是脉诊专名，症状域不会用，
#              故可无歧义判定。
#   舌诊专名 —— 尖/根/苔 打头（尖红=舌尖红、根苔=舌根苔、苔平）。同理。
# 这类省略写法**不限两字**：实测存量里 3~7 字的描述性短语反而居多
# （尺偏沉 124、根部厚腻 129、寸后及关前反中取明显 6），只判两字会漏掉 3057 条。
_PULSE_HEADS = ("尺", "寸", "关")
_TONGUE_HEADS = ("尖", "根", "苔")
# ④ 舌象专名（批次 21 补）：不以「舌/苔」起头但确属舌诊的写法
#    `边尖略红`（舌边尖）、`边齿痕`（舌边）、`根苔厚腻`（舌根）。
#    ⚠️ 不另立常量（C2）：脉位直接复用 `_PULSE_HEADS`；舌象在既有 `_TONGUE_HEADS`
#    基础上只补「边 / 齿痕」（原常量只含 尖/根/苔）。同一件事两套常量就是漂移的起点。
_TONGUE_HEADS_EX = _TONGUE_HEADS + ("边", "齿痕")
_PT_HEADS = _PULSE_HEADS + _TONGUE_HEADS


def _seg_cjk(seg: str) -> bool:
    """整段是否全为汉字（用于无前缀舌脉专名的判定，挡掉 `尺>`、`寸及` 一类抽取噪声）。"""
    return bool(seg) and all("\u4e00" <= ch <= "\u9fff" for ch in seg)
# 既无前缀、首字也不属于上述专名的脉象组合词，逐个列（都是「脉象」的固定合称）
_PT_WHITELIST = ("动惕", "动剔", "弦滑", "浮滑", "浮数")
# 首字撞上专名但**语义不是舌脉**的前缀，一律排除（踩过的坑，均为症状域真词）：
#   关节 —— 关节痛/关节怕冷/关节屈伸不利 等，实测 90+ 个不同写法都在症状里；
#   尺肤 —— 尺部皮肤（诊断体征），尺肤热/尺肤凉/尺肤粘，与脉象是两回事；
#   苔藓 —— 皮肤病（苔藓/苔藓样皮损），不是舌苔；
#   尖锐 —— 声音/湿疣等义（尖锐声），脉象里没有「尖锐」；
#   脉下 —— 脉位下方的部位描述（左脉下有一囊肿），是症状不是脉象。
_PT_BLACKLIST = ("关节", "尺肤", "苔藓", "尖锐", "脉下")
# 方位 / 数量前缀：脉位常写成「两关偏浮」「右寸后关前」「双寸关」「左关偏浮」——
# 首字不是三部，只判首字会漏（2026-10-19 实测存量这批 4318 条）。
# 只剥**一个**字符：既覆盖上述写法，又不会把「两手手指略自温」「左侧肩颈痠痛」
# 这类症状误收（剥掉首字后仍不以舌脉专名开头）。
_PT_QUALIFIERS = ("左", "右", "双", "两")


def _pt_core(content: str) -> str:
    """剥掉一个可选的方位/数量前缀（左/右/双/两），供首字判据使用。"""
    return content[1:] if content[:1] in _PT_QUALIFIERS else content


def _is_pulse_tongue(content: str) -> bool:
    """该实体是不是脉象/舌象要素（不管带不带「脉」「舌」前缀，允许一个方位前缀）。"""
    if not content:
        return False
    core = _pt_core(content)
    # 黑名单在「原字」与「剥前缀后」两处都判：'左关节痛' 剥掉「左」后要能看到「关节」
    if content.startswith(_PT_BLACKLIST) or core.startswith(_PT_BLACKLIST):
        return False
    if core.startswith(("脉", "舌")):
        return True
    if core.startswith(_PT_HEADS):
        # 抽取噪声（如 "尺>"、"寸及"、"关尺>寸"）：含非汉字的一律不当舌脉
        return all("\u4e00" <= ch <= "\u9fff" for ch in content)
    return content in _PT_WHITELIST


def _pt_is_tongue(content: str) -> bool:
    """该舌脉要素属舌象（舌/尖/根/苔 打头，允许一个方位/数量前缀）。"""
    return _pt_core(content).startswith(("舌", "尖", "根", "苔"))


def _reroute_pulse_tongue(resp: ExtractResponse) -> None:
    """把落在 `symptoms` 里的脉象 / 舌象要素移回 `pulseList` / `tongueList`。

    为什么需要这一步：`LABEL_FIELD` 只有 病名/症状/证型/方剂/本草 五个模型标签
    （模型能输出的标签集就是这 5 个，见其注释），**没有「脉象」「舌象」** ——
    模型遇到「脉细数」只能把它标成「症状」。而规则侧（`_rules`）又会把同一批词
    收进 `pulseList` / `tongueList`，于是同一条要素两处都有：

    * 症状归一率的分子分母被这批词拉低（它们本不属于症状）；
    * 后端质量报告会把它统计成「脉象、舌象被当成症状」的错放（实测 3978 条）；
    * 出厂默认舌象/脉象不计未归一扣分（见 `QcRuleSet.defaultStandardization`），
      可它们落在 `symptoms` 里（该列默认计分）⇒ 反而被算进了「术语未标准化」扣分。

    **移动而不是丢弃**：规则没抽到的那部分脉/舌要素（实测占错放项的 59%）原本
    只存在于 `symptoms`，直接删会连抽取结果一起丢掉。

    判据见 {@code _is_pulse_tongue}：除「脉」「舌」前缀外，还覆盖书写时省略前缀的
    脉诊三部（尺/寸/关）与舌诊专名（尖/根/苔），且不限于两字 —— 2026-10-15 实测
    这批无前缀的共 3057 条 / 2384 条记录（其中 2 字词 850 条，3 字以上 2207 条），
    只判两字会漏掉三分之二。
    """
    kept: List[Entity] = []
    for e in resp.symptoms:
        c = (e.content or e.sourceText or "").strip()
        if not _is_pulse_tongue(c):
            kept.append(e)
        elif _pt_is_tongue(c):
            _append_unique_by_content(resp.tongueList, e, c)
        else:
            _append_unique_by_content(resp.pulseList, e, c)
    resp.symptoms = kept


def _append_unique_by_content(target: List[Entity], entity: Entity, content: str) -> None:
    """按 `content` 去重追加：规则侧已经抽到的同一个脉/舌要素不再重复登记。"""
    if any((x.content or x.sourceText or "").strip() == content for x in target):
        return
    target.append(entity)
