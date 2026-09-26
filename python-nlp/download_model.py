"""预下载 Monor/hwtcmner 到本地 python-nlp/model/（供离线/演示稳定使用）。

用法：
    python download_model.py
直连 HuggingFace 不通时自动走 hf-mirror.com 镜像（可用环境变量 HF_ENDPOINT 覆盖）。
"""
import os
from pathlib import Path

# 上游模型仓库（RoBERTa 中医 NER，13 标签 BIO）
MODEL_ID = "Monor/hwtcmner"
# 脚本所在目录，模型固定落在它下面的 model/
HERE = Path(__file__).resolve().parent
LOCAL_DIR = HERE / "model"

# 只取推理必需的文件：权重与分词器，跳过 README 等训练产物
ALLOW = [
    "config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "vocab.txt",
    "pytorch_model.bin",
    "*.safetensors",
]


def main():
    """把模型快照下载到本地 model/ 目录，已存在则增量补齐。"""
    # 1. 未指定源站时走国内镜像（直连 HuggingFace 常不可达）
    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
    from huggingface_hub import snapshot_download

    # 2. 建目录并下载，allow_patterns 之外的文件不落盘
    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[model] 下载 {MODEL_ID} -> {LOCAL_DIR}")
    print(f"[model] endpoint = {os.environ['HF_ENDPOINT']}")
    path = snapshot_download(repo_id=MODEL_ID, local_dir=str(LOCAL_DIR), allow_patterns=ALLOW)
    print(f"[model] 完成: {path}")


if __name__ == "__main__":
    main()
