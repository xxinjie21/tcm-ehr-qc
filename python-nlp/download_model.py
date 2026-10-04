"""预下载 Monor/hwtcmner 到本地 python-nlp/model/（供离线/演示稳定使用）。

用法：
    python download_model.py
    python download_model.py --verify     # 只校验本地模型指纹，不下载
直连 HuggingFace 不通时自动走 hf-mirror.com 镜像（可用环境变量 HF_ENDPOINT 覆盖）。

为什么钉死 revision：NER 结果直接进 structured_data 并参与质控评分，
上游主分支一旦更新，同一份病历的抽取结果就会变 —— 既有数据不可复现、
批次之间无法比较。批次 23 起 MODEL_REVISION 固定为实测使用的那个 commit。
"""
import argparse
import hashlib
import os
from pathlib import Path

# 上游模型仓库（RoBERTa 中医 NER，13 标签 BIO）
MODEL_ID = "Monor/hwtcmner"

# 钉死的版本：本地 model/ 里 6 个文件的 .metadata 第一行全部是这个 commit，
# 说明它们来自同一次下载。不钉的话上游更新后重新下载会得到不同的抽取结果。
MODEL_REVISION = "d61d95ac41ef68d455a0571ac7d6b96dbb6f7299"

# 权重文件指纹：即使有人手动替换了 model/ 里的权重，也能立刻发现
WEIGHTS_SHA256 = "0845ff75b9c5688d857049660691bca57fd8c5e67559f294b0e307a539115717"

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


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify() -> int:
    """校验本地模型是否就是钉定的那个版本；不一致返回非 0 退出码。"""
    weights = LOCAL_DIR / "pytorch_model.bin"
    if not weights.is_file():
        print(f"[model] 校验失败：{weights} 不存在，请先运行 python download_model.py")
        return 1
    actual = sha256_of(weights)
    if actual != WEIGHTS_SHA256:
        print("[model] 校验失败：权重指纹与钉定版本不一致")
        print(f"        期望 {WEIGHTS_SHA256}")
        print(f"        实际 {actual}")
        print("        若确实要换模型版本，请同步更新 MODEL_REVISION 与 WEIGHTS_SHA256，")
        print("        并在《多批次实施计划》里登记 —— 否则历史抽取结果不可复现。")
        return 1
    print(f"[model] 校验通过：{MODEL_ID}@{MODEL_REVISION[:12]}")
    print(f"        权重 sha256 = {actual}")
    return 0


def main():
    ap = argparse.ArgumentParser(description="预下载/校验 NER 模型")
    ap.add_argument("--verify", action="store_true", help="只校验本地模型指纹，不下载")
    args = ap.parse_args()

    if args.verify:
        raise SystemExit(verify())

    # 1. 未指定源站时走国内镜像（直连 HuggingFace 常不可达）
    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
    from huggingface_hub import snapshot_download

    # 2. 建目录并下载，allow_patterns 之外的文件不落盘；revision 钉死
    LOCAL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[model] 下载 {MODEL_ID}@{MODEL_REVISION[:12]} -> {LOCAL_DIR}")
    print(f"[model] endpoint = {os.environ['HF_ENDPOINT']}")
    path = snapshot_download(repo_id=MODEL_ID, revision=MODEL_REVISION,
                             local_dir=str(LOCAL_DIR), allow_patterns=ALLOW)
    print(f"[model] 完成: {path}")

    # 3. 下完立刻自校验：指纹不符要当场发现，不能等到跑抽取时才发现
    if verify() != 0:
        raise SystemExit("下载完成但指纹校验失败")


if __name__ == "__main__":
    main()
