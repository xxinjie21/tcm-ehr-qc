"""预下载 Monor/hwtcmner 到本地 python-nlp/model/（供离线/演示稳定使用）。

用法：
    python download_model.py
直连 HuggingFace 不通时自动走 hf-mirror.com 镜像（可用环境变量 HF_ENDPOINT 覆盖）。
"""
import os
from pathlib import Path

MODEL_ID = "Monor/hwtcmner"
HERE = Path(__file__).resolve().parent
LOCAL_DIR = HERE / "model"

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
    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
    from huggingface_hub import snapshot_download

    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[model] 下载 {MODEL_ID} -> {LOCAL_DIR}")
    print(f"[model] endpoint = {os.environ['HF_ENDPOINT']}")
    path = snapshot_download(repo_id=MODEL_ID, local_dir=str(LOCAL_DIR), allow_patterns=ALLOW)
    print(f"[model] 完成: {path}")


if __name__ == "__main__":
    main()
