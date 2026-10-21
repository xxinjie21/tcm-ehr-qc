# 交接单：TCM 病历术语归一（引擎侧）→ DeepSeek harness

> 交接时间：2026-10-20 00:35 ｜ 分支 `feat/import-dedup-normalize-retry-multinlp` ｜ HEAD `6785c99`
> 本文件未跟踪，不随提交进仓库。上游旧版 `docs/handoff-workbuddy-2026-10-19.md` 已删除（内容作废）。

---

## 0. 一句话现状

引擎侧改造（否定丢弃 / 噪声过滤 / 原文口径 / 脉·舌·症状·证候拆解 / 脉舌串列修正 / 单字别名护栏 / 重跑清旧痕迹 / 舌象脉象跳过字扩容）**全部完成，633/633 单测绿，已跑完「仅归一」+ 质控两轮全库验证，并验证幂等**。后端正在运行最新代码（PID 63376，23:16:54 启动；类编译于 23:15:54）。

**剩下的瓶颈不在引擎，在词典与抽取层**，往下看 §7/§8。

---

## 1. 任务与硬约束

**目标**：把各类型归一率推高，同时不牺牲正确率。用户要求「各类型 ≥90%」，但已确认症状 90% 在现有结构下不可达（见 §10）。

**用户硬约束（全程有效）**
- 前端：不改配色/色值（只改位置、布局、间距、结构、交互）；同功能同组件、跨页外形一致；加载态必须有动画、骨架不得遮字、禁整块 `v-loading`；尽量一屏；统一组件内 padding，用 sp 令牌禁字面量 px；全局统一控件 size。
- 提交：逐任务 `git add` 指定文件，**绝不 `-A`**；工作区有 ~111 处无关改动（`docs/` 删除、`docs/visual-bench/*.png`、`frontend/src/**` P1 重写、未跟踪 `LoadingSkeleton.vue`/`ScopeBar.vue`/`autoheight.js`）。**未获用户明确要求不得提交。**
- 精度口径：**宁可未命中，不错映射**；子串等价一律由别名表决定。
- 拆解口径：**整串被词典项完整覆盖才拆**，任一残字（含否定/关系字）整体放弃。

---

## 2. 当前指标（唯一可信数据）

来源：`e0bc32fb-5ac9-455e-b68e-ce1b21a75a25`（仅归一，34154 条，460s）。
幂等复验：`2c160956-434b-4a1f-9e14-be691f568f49`，**两次逐项完全一致**。
质控：`87bde92e-5e3a-434f-ade2-b2dd9eacb749`（34154 条，220s，failed=0）。

| 字段 | 总数 | 已归一 | 归一率 |
|---|---|---|---|
| diseases | 33868 | 27929 | 82.5% |
| symptoms | 198195 | 72285 | **36.5%** |
| patternList | 53424 | 33178 | 62.1% |
| tongueList | 76991 | 58468 | **75.9%** |
| pulseList | 80773 | 74162 | **91.8%** |
| herbs | 443883 | 437361 | 98.5% |
| formulaList | 938 | 672 | 71.6% |
| treatmentList | 5 | 2 | 40.0% |
| causeList | 30862 | 0 | **0.0%** |

质控：avg **85.26**；合格 28178 / 待复核 2367 / 无效 3609；封顶（`术语未标准化` points≥5）**14709 = 43.1%**；termItems 27953。

复现：
```powershell
$env:PYTHONIOENCODING="utf-8"
F:\python3.14\python.exe C:\Users\xxinj\AppData\Local\Temp\opencode\run_t12.py norm 2400
F:\python3.14\python.exe C:\Users\xxinj\AppData\Local\Temp\opencode\db_norm_stats.py
F:\python3.14\python.exe C:\Users\xxinj\AppData\Local\Temp\opencode\run_t12.py qc 1800
F:\python3.14\python.exe C:\Users\xxinj\AppData\Local\Temp\opencode\qc_stats.py
```

---

## 3. 引擎改动（按文件，行为准）

### `common/utils/EsTermNormalizer.java`
| 位置 | 内容 |
|---|---|
| L278 `decompose` | 按 type 分派 pulse/tongue/symptom/pattern |
| L299 `decomposePulse` | 顺序：`PULSE_SKIP_PHRASES`（不受按/不受/按之/应指/按）→ 脉位 → `PULSE_BLOCK` → `PULSE_RELATION` → 脉质；脉位判定先于关系字（保住「左寸沉」） |
| L402 `decomposeTongue` | `cover` + `TONGUE_SKIP` |
| L433 `decomposeSymptom` | `cover` + `SYMPTOM_PREFIX/SUFFIX`；整串失败时 `exactTerm` 定点查词，再 `normalize` 兜底须 EXACT |
| L485 `decomposePattern` | `cover` + `PATTERN_SKIP/SUFFIX` + `expandByProbe` 逐段补词 |
| L530 `surfaces` | 标准词/别名 → 标准词；**单字 surface 一律不收** |
| L555 `expandByProbe` / L614 `cover` / L373 `isKnown` | 拆解基础设施（cover 记忆化，长词优先、取最少段） |
| L685/L690 `scan` → L897 `containsAnyAlias` | **R8**：别名码点 <2 跳过 |
| L738 `judge` | R2/R3/R4/R7 + 极性否决 + **R8**（L814 `containsAsPartOfInput` 同护栏） |

常量现值：
- `PULSE_MODIFIERS = "偏略稍较微甚尤仍尚"`
- `PULSE_RELATION = "脉前后中上及取双两左右下带"`
- `PULSE_BLOCK = "不无未否欠除减乏兼"`、`PULSE_POSITIONS = 左/右+寸关尺`
- `PULSE_QUALITY = "浮沉迟数虚实滑涩弦紧细濡微弱洪大促结代疾动牢革散伏长短缓芤旺软小"`
- `TONGUE_SKIP = "偏略稍较微甚尤质边有部根尖内外前后中底满布仍尚体面"`
- `SYMPTOM_PREFIX`（含 活动后/晨起/夜寐/连声/刷牙/寐时/蹲起时/挠抓后/天热/有时/夏冬季/少量…）、`SYMPTOM_SUFFIX`（明显/加重/彻全身/一般/黄染/作咳…）、`SYMPTOM_BLOCK = "不无未否欠除减"`
- `PATTERN_SKIP = "兼夹及为和、，,主略偏稍较甚尤微"`、`PATTERN_SUFFIX = {为主兼证,为主兼,为主症,为主证,偏盛,为主}`、`PATTERN_BLOCK = "不无未否欠除减"`

### `common/utils/EntityNormalizer.java`
| 位置 | 内容 |
|---|---|
| L425 `normalize(vo, orgId)` | prefetchRecall → moveMisplacedPulseTongue → dropSingleCharFragments → 8 类 normEntities → dropNoiseFragments → 去重 |
| L260 `moveMisplacedPulseTongue` | 判据 = `hitsDictionary` **或** `decompose` 拆得出词条 |
| L576 `normEntities` | `rawSpan` 取原文；否定+包含命中 → **整条丢弃**；拆解产物逐段入库 |
| **L594** | **清旧痕迹**：`normLevel != null && content != sourceText` → `content = sourceText`、`normLevel = null`，本轮按原文重算（抽解器自带的同义映射 `normLevel==null` 不动） |
| L542 `dropNoiseFragments` / L560 `isNoiseFragment` | 只丢 `normLevel == null` 的碎片；常量 L509 `NOISE_BARE`、L518 `NOISE_DESC`、L527 `NOISE_SEVERITY` |
| L859 `rawSpan` | 优先 `sourceText`，无则 `content` |

### 测试（`mvn -o test` = 633 tests, 0 failure）
- 新建 `EntityNormalizerCleanupTest`（7）、`EsTermNormalizerDecomposeTest`（20）、`EntityNormalizerDecomposeTest`（4）
- 改 `EntityNormalizerNegationGuardTest`、`EsTermNormalizerContainGuardTest`（+R8 反例）

---

## 4. 必须遵守的不变量

**R1–R8**
| 规则 | 内容 |
|---|---|
| R1 | 输入 <2 码点只走精确 |
| R2 | 禁 `std.contains(input)` |
| R3 | 允许 `input.contains(std\|alias)`，长度差 ≤ `elasticsearch.contain-delta`(=1) |
| R4 | 禁 `alias.contains(input)` |
| R5 | 否定语境不归一；包含命中 + 否定 → **整条丢弃** |
| R6 | 精确两轮（std 全候选 → alias 全候选，取最短） |
| R7 | 模糊级否决子串关系 |
| R8 | **单字别名只参与召回与精确命中，不参与包含**（`质软→脉软`、`吐痰→呕吐`、`湿疹→斑疹` 都是 1 字别名放进来的） |

**清旧痕迹（L594）为什么必须有**：`normEntities` 原先只在命中时写 `content/normLevel`，未命中直接保留旧值 → 撤回一条误映射后，存量库的旧映射**永远撤不掉**（实测 R8 撤回后计数零变化）。加上重置后：规则改动立刻在存量数据可见、重跑幂等（已验证两次结果一致）。

**其他**
- 未命中绝不写 `normLevel`、不改 `content`。
- 噪声过滤只作用于 `normLevel == null`。
- `moveMisplacedPulseTongue` 必须由词典/拆解证明，不许按词形猜。
- 拆解是破坏性的（一拆多），只在 `!isHit || (isHit==CONTAIN && !negated)` 时尝试。
- 拆解产物的 `sourceText` = 整串原文，`content` = 标准词；统计以 `content` 为准。

---

## 5. 未提交改动

**本轮引擎/测试（我改的）**
```
M  .../common/utils/EsTermNormalizer.java
M  .../common/utils/EntityNormalizer.java
M  .../test/.../EntityNormalizerNegationGuardTest.java
M  .../test/.../EsTermNormalizerContainGuardTest.java
?? .../test/.../EntityNormalizerCleanupTest.java
?? .../test/.../EntityNormalizerDecomposeTest.java
?? .../test/.../EsTermNormalizerDecomposeTest.java
```
**用户/豆包导入（我未改内容）**
```
M  .../dictionaries/{diseases,patterns,pulses,symptoms,tongues}.json
```
**来源存疑，提交前先看 diff**：`LlmClient.java`(+2/-2)、`QcScorerTest.java`、`LogicCheckerTest.java`

---

## 6. 用户已拍板 / 待拍板

**已拍板**
1. 词典由**豆包**做，我们只给规范与清单（§8）。
2. 碎片策略：**只映射语义明确的**（`大便日` → `大便日一行`），纯残片（`小便色`/`夜寐一`/`大便一`）留作未归一 / 交抽取层过滤。
3. 部位前缀：**已定为「复合发现独立成词」**，不做引擎统一剥前缀（见 §7）。

**待拍板**
1. `causeList`（0%，30862 条）这次是否也交给豆包一起做（对总分拉动最大，但从零起步）。
2. 本轮改动是否提交、分几次提交。

---

## 7. 关键结论：部位前缀（我有一次错判，务必看）

我先前说「部位前缀 1.36 万条、可 +7pp」**是错的**。严格按「部位词 + 剥掉后恰好等于词典 surface」实测只有 **1325 条（+0.7pp）**，且大头合并会丢语义：

| 合并 | 条数 | 判断 |
|---|---|---|
| 咽喉有痰 → 有痰 | 163 | 可接受（sourceText 保留部位） |
| 全身乏力 / 四肢乏力 → 乏力 | 68 | 可接受 |
| 胃脘嘈杂 → 嘈杂 | 42 | 可接受 |
| 下肢有汗 / 下肢汗少 → 有汗 / 汗少 | 77 | **不可接受**（局限出汗 ≠ 全身） |
| 腋下汗出 / 腋下黄汗 → 汗出 / 黄汗 | 52 | **不可接受**（腋汗独立体征） |
| 肛门瘙痒 → 瘙痒 | 32 | **不可接受**（独立病证） |

→ 定案：**`腋下汗出`、`肛门瘙痒`、`下肢汗少` 这类整串登记为标准词**（别名收同义写法），引擎不动代码。

---

## 8. 给豆包的词典任务书（可直接转发）

**交付方式（二选一）**
1. 改 `java-backend/src/main/resources/dictionaries/symptoms.json`，格式 `[{"standardTerm":"大便日一行","aliases":["大便每日一次","大便一天一次"]}]`；重启后自动重播基础层 + 重建索引。
2. 或 CSV/xlsx 两列（`standardTerm,alias`）→ `POST /api/dictionary/import`（multipart：`file` + `type=symptom` + `target=base`）→ `POST /api/dictionary/reindex`。

**6 条硬规则**
1. 一个标准词 = 一个能独立判断的临床发现，不写整句。
2. **正常发现也要登记**（大便成形 / 夜寐一般 / 食量一般 / 小便自利）——是问诊结论。
3. **含部位的复合发现独立成词，不与无部位词合并**。
4. 否定式本身是发现时登记为**标准词**（食欲欠佳 / 夜寐欠安 / 精神欠佳），别当别名（别名走包含判定会被否证规则拦）。
5. 别名只收同义改写与数量变体，**不收残缺片段**；唯一例外：语义唯一的截断式（`大便日`）。
6. **别名 ≥2 字**（单字别名只做精确命中，等于白加）；不造词，用规范中医用语。

**分桶目标（括号=现库未归一条数）**

| 桶 | 标准词候选 | 频次 |
|---|---|---|
| 大便 | 大便日一行（日1行/一日一次/一天一次/每日/日一次/日行一次）、大便二日一行、大便一日多次、大便成形、大便偏干、大便秘结、大便溏、大便不畅 | 3950（755 种写法） |
| 小便 | 小便色淡黄、小便色黄、小便色深黄、小便清长、小便频数、小便自利 | 1927（460） |
| 睡眠 | 夜寐一般、夜寐欠安（欠佳/不佳/欠安稳）、入睡困难、夜寐易醒、夜寐辗转、多梦、嗜睡 | 3246（992） |
| 饮水 | 饮水多、饮水少、饮水一般、喜温饮、喜冷饮 | 2170（271） |
| 食欲/食量 | 食欲可、食欲欠佳、食量一般、食量少、食量多 | 1405（209） |
| 喜好 | 喜肉食、喜甜食、喜辛辣、喜厚味、喜叹息、喜趴睡 | 2038（559） |
| 卧位 | 喜俯卧、喜仰卧、喜侧卧 | 334（53） |
| 精神 | 精神可、精神欠佳、精神差、疲乏 | 558（103） |
| 月经 | 按妇科口径（周期/经量/经色/痛经/经期乳胀…） | 836（379） |
| 面色 | 面色红润、面色萎黄、面色㿠白、面色晦暗 | 555（165） |

合计 ≈ **1.5 万条 ≈ 症状 +5.5pp**（36.5% → 约 42%）。

**验收（我们执行）**：导入 → 「仅归一」8 分钟（**不需要重抽**）→ 指标对比 → 抽 30 条人工核对有无错合并 → 复跑验幂等。

**另可交给豆包的输入**：未归一 top500 清单（执行时从库导出，比凭经验列词准）；`cause` 病因词表（若拍板做）。

---

## 9. 环境与运维

MySQL :3306 `tcm_ehr` root/123456；Redis :6379 pass 123456 db1；ES :9200；后端 :8080；Python NLP :8001；前端 :3000。
JDK `F:\jdk17`；Maven `D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd`；Python `F:\python3.14\python.exe`；mysql CLI `D:\mysql\bin\mysql.exe`；admin/123456；org `grp-default-2026`。

```powershell
$env:JAVA_HOME="F:\jdk17"
& "D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd" -o test          # cwd = java-backend
& "D:\devSoft\apache-maven-3.9.16\bin\mvn.cmd" -o test "-Dtest=A,B"
```

**重启**（`Stop-Process`/`Start-Process` 常报 `ChildProcess.kill` 假错，用端口 + StartTime 判定）：
```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Start-Sleep -Seconds 5
$cp=(Get-Content C:\Users\xxinj\AppData\Local\Temp\opencode\cp.txt -Raw).Trim()
$cp="D:\ZISHIKU\AI\tcm-ehr-qc\java-backend\target\classes;"+$cp
Start-Process F:\jdk17\bin\java.exe -ArgumentList @("-Dfile.encoding=UTF-8","-classpath",$cp,"com.tcm.ehr.TcmEhrApplication") `
  -WorkingDirectory "D:\ZISHIKU\AI\tcm-ehr-qc\java-backend" -WindowStyle Hidden `
  -RedirectStandardOutput "C:\Users\xxinj\AppData\Local\Temp\opencode\backend.restart.log" `
  -RedirectStandardError "C:\Users\xxinj\AppData\Local\Temp\opencode\backend.restart.err.log"
# 校验：$p=(Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess; Get-Process -Id $p | select Id,StartTime
#       StartTime 必须晚于 target/classes 的 LastWriteTime
```

**词典生效**：`DataInitializationListener` 启动比对 json 指纹与库内版本 → 整层重播 → `dictionary_terms` → ES。改 json 重启即生效；组织层不动。

**接口**：`POST /api/auth/login {"username":"admin","password":"123456"}` → `data.token`；`POST /api/nlp/extract {"text":"..."}`（响应字段 **`diseases`**）；`POST /api/governance/normalize {type,term}`（只走 normalize，不含拆解，适合查单条命中层级）；批跑 `/api/nlp/extract/batch/normalize-only`、`/api/qc/score/batch`。

**坑（都踩过）**
- PS 5.1 `>` 重定向写 UTF-16LE → 中文乱码；要写文件就用 Python `io.open(..., encoding='utf-8')`。
- PS here-string 内嵌引号/三引号易 SyntaxError → **写临时 `.py` 文件再跑**。
- `python -c` 传参时 PS 会吞双引号 → 用单引号包整段、内部字符串用 `''`（PS 里代表一个 `'`）。
- 控制台中文需 `$env:PYTHONIOENCODING="utf-8"`；没有 `rg`，用 grep 工具 / `Select-String`。
- 数据库表 `nlp_task`（单数）；`records` 列 `registration_no/structured_data/qc_results/score/grade/org_id/status/governed/manually_edited`（无 `past_history`）。

**临时脚本**（`C:\Users\xxinj\AppData\Local\Temp\opencode\`）
`run_t12.py`（norm|qc [maxWait]）、`db_norm_stats.py`、`qc_stats.py`、`qc_grade.py`、`qc_probe.py`、`dict_gap.py`（A/B/N 分桶）、`unnorm_top.py`（未归一 top + 是否本就是标准词）、`tongue_sim.py`（拆解收益模拟）、`proposal.py`（词典提案）、`split_gap.py`、`pat_sim*.py`、`sym_bucket.py`、`misfit.py`、`pulse_src_check.py`、`api_check*.py`、`cp.txt`。

---

## 10. 已定论 / 不要翻案

1. `disease` 全字覆盖拆解无收益，不实现。
2. 部位前缀不做统一剥离（§7，改为复合词独立成词）。
3. **症状 90% 在现有结构下不可达**：A 类（内含标准词、可登记别名）上限 **52.7%**；B 类 84211 条（distinct 36765）不含任何标准词，需要动抽取层或改术语边界。
4. 症状未命中的形状证据：平均 **5.3 字**（命中 3.1 字），45% ≤4 字 → 抽取层把短语/整句切成了实体（`大便日一行` 469、`小便色略黄` 216、`两手指端略自温` 132）。
5. `causeList` 0% 是纯缺口（`causes.json` 空 + 无 `term_cause` 索引），不是引擎 bug。
6. `treatmentList` 只有 5 条实体，不投入。
7. R8 不要因「计数没涨」回退：它的价值是撤掉误映射。
8. 「仅归一」重跑的语义已改为**按规则重算**（不再只增不减），指标短期下降属正常，汇报时必须解释。

---

## 11. 下一步（按顺序）

1. 等用户确认：`cause` 是否一起交给豆包；本轮改动是否提交。
2. 导出三份给豆包的输入：`症状未归一 top500`、`分桶别名建议`、`部位复合词清单`（用 `dict_gap.py` / `unnorm_top.py` / `proposal.py` 产出）。
3. 豆包交回 json/CSV → 导入 → `run_t12.py norm` → `db_norm_stats.py` → 抽 30 条人工核对 → 再跑一次验幂等 → `run_t12.py qc` + `qc_stats.py`。
4. 若做 `cause`：建 `term_cause` 索引 + 基础层重播 → 重跑归一/质控。
5. 中期（真正的解法）：抽取层术语边界（把二便/睡眠/饮食/卧位/喜好类描述切到独立字段或整体丢弃），然后全库重抽（~1h，约 10 条/s）。