"""NLP 抽取服务的 Pydantic 模型与共享字段清单。

单独成文件，是为了让三层都能引用它而不产生循环依赖。依赖方向固定为：

    schemas  <-  nlp_rules  <-  nlp_model  <-  main

（main 只做 HTTP 层与启动钩子；nlp_model 是唯一 import torch 的模块。）
"""
from typing import List, Optional

from pydantic import BaseModel, Field

# 单次请求的文本上限（字符）。超长由 ExtractRequest 的 Field 拦成 422，而不是悄悄截断。
# Java 侧 NlpExtractDTO 的 @Size(max = 20000) 与此同值 —— 改一处必须同时改另一处，
# 否则超长文本会走到这里拿 422，再被 Java 侧笼统降级成「服务不可用」。
MAX_TEXT_CHARS = 20000

# 9 类结构化字段的固定顺序（响应组装与前端展示都按它走）
FIELDS = ["diseases", "symptoms", "tongueList", "pulseList", "patternList",
          "causeList", "treatmentList", "formulaList", "herbs"]


class ExtractRequest(BaseModel):
    text: str = Field(max_length=MAX_TEXT_CHARS)


class Entity(BaseModel):
    content: str
    sourceText: str
    confidence: Optional[float] = None
    source: str = "model"


class Herb(BaseModel):
    name: str
    dosage: Optional[str] = None
    sourceText: str
    confidence: Optional[float] = None
    source: str = "model"


class ExtractResponse(BaseModel):
    diseases: List[Entity] = []
    symptoms: List[Entity] = []
    tongueList: List[Entity] = []
    pulseList: List[Entity] = []
    patternList: List[Entity] = []
    causeList: List[Entity] = []
    treatmentList: List[Entity] = []
    formulaList: List[Entity] = []
    herbs: List[Herb] = []
    modelAvailable: bool = False
    # 是否被截断：长文本现由 _ner 分窗口覆盖整段，恒为 False（保留该字段只为兼容既有 Java / 前端接口）
    truncated: bool = False
