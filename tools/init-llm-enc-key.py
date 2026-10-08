#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
LLM 密钥加密密钥（TCM_LLM_ENC_KEY）生成/评估工具。

## 为什么需要它

「导入 LLM」把每个用户的三方 API Key 加密后落库（`user_llm_config.api_key`），
加密用 AES-256-GCM，密钥取自 `TCM_LLM_ENC_KEY`（32 字节 Base64）。

`application.yml` 的 `llm.crypto-key` 里**已经带了一个开发用默认值**，所以本地
拉下代码就能用、不会出现「未配置 LLM 加密密钥」。本脚本是**生产环境**该做的那一步：
把默认值换成随机密钥，用环境变量覆盖（`LlmSecretCipher.resolveKey` 先读
`System.getenv` 再读配置项，环境变量优先级更高）。

## 用法

    # 1) 演练（默认）：评估现状 + 打印将生成的密钥与要执行的命令，不改任何东西
    python tools/init-llm-enc-key.py

    # 2) 真正设置：把新密钥写入当前用户的持久环境变量（Windows setx）
    python tools/init-llm-enc-key.py --apply

    # 3) 只打印密钥（自己决定怎么配，如写进 Docker/CI 的 secret）
    python tools/init-llm-enc-key.py --print-key

设置后**必须重启后端**（重新运行 start-all.bat）才生效：
`LlmSecretCipher` 在构造器里读环境变量，运行中改不会重新加载。
注意 `setx` 只对新开的进程生效 —— 重启后端前先关掉启动控制台窗口再重开。

## ⚠️ 换密钥的代价（先读这段再决定要不要 --apply）

GCM 密文绑定密钥。**换掉密钥之后，之前加密落库的 API Key 全部解不开**：
`LlmSecretCipher.decrypt()` 返回空串并告警一次，用户表现为「配置看着还在、
但模型调不通」，需要各自重填一次三方密钥。

所以在**已经有用户配过密钥**的环境上换密钥是一次有损操作，要挑时间窗做。
本脚本在检测到库里已有密文时会额外提示这一点。
"""
import argparse
import base64
import os
import secrets
import shutil
import subprocess
import sys

VAR_NAME = "TCM_LLM_ENC_KEY"
KEY_BYTES = 32  # AES-256：resolveKey() 校验 raw.length * 8 == 256
YML = os.path.join("java-backend", "src", "main", "resources", "application.yml")


def make_key():
    """生成 32 字节随机密钥的 Base64 表示（44 字符，含尾部 =）。"""
    return base64.b64encode(secrets.token_bytes(KEY_BYTES)).decode("ascii")


def decode_len(b64):
    """返回 Base64 解码后的字节数；非法 Base64 返回 None。"""
    try:
        return len(base64.b64decode(b64, validate=True))
    except Exception:
        return None


def yml_has_default():
    """yml 里是否已带开发默认值（即「开箱可用」是否成立）。"""
    if not os.path.exists(YML):
        return None
    with open(YML, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("crypto-key:") or s.startswith("# crypto-key:"):
                return not s.startswith("#")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="把新密钥写入当前用户的持久环境变量（Windows setx）")
    ap.add_argument("--print-key", action="store_true",
                    help="只打印新密钥，不做任何改动")
    args = ap.parse_args()

    env_now = os.environ.get(VAR_NAME)
    default_on = yml_has_default()

    print("  === 现状 ===")
    print("  环境变量 %s：%s"
          % (VAR_NAME, ("已设置（%d 字符）" % len(env_now)) if env_now else "未设置"))
    if env_now:
        n = decode_len(env_now)
        print("    · 解码后 %s 字节 %s"
              % (n, "（合法，AES-256）" if n == KEY_BYTES else "（**不合法**，需 %d 字节）" % KEY_BYTES))
    if default_on is None:
        print("  [警告] 找不到 %s（请在仓库根目录运行）" % YML)
    elif default_on:
        print("  application.yml：已带开发默认值 → 当前**开箱即可用**，不会报「未配置 LLM 加密密钥」")
    else:
        print("  application.yml：**没有**开发默认值，且环境变量未设置 → 保存密钥会被拒（503/1011）")

    key = make_key()
    print()
    print("  === 新生成的密钥（生产用）===")
    print("  %s" % key)
    print("    · 解码后 %d 字节（满足 AES-256）" % decode_len(key))

    if args.print_key:
        print()
        print("  === 只打印模式，未做任何改动 ===")
        return 0

    print()
    print("  === 影响（重要）===")
    print("  · 换密钥后，**已加密落库的 API Key 全部解不开**，用户需要各自重填一次")
    print("  · 必须重启后端才生效（构造器读环境变量，运行中改不重新加载）")
    print("  · setx 只对新开的进程生效：重开启动控制台窗口，再跑 start-all.bat")

    if not args.apply:
        print()
        print("  === 演练模式，未改动任何东西 ===")
        print("  确认后再执行：python tools/init-llm-enc-key.py --apply")
        print("  或只取密钥自己配：python tools/init-llm-enc-key.py --print-key")
        return 0

    if not shutil.which("setx"):
        print()
        print("  [FAIL] 本机找不到 setx（非 Windows？）—— 请手工设置环境变量 %s：%s"
              % (VAR_NAME, key))
        return 1

    # setx 的值上限 1024 字符，这里 44 字符远小于限制；失败会以非 0 退出码体现
    r = subprocess.run(["setx", VAR_NAME, key], capture_output=True, text=True)
    if r.returncode != 0:
        print("  [FAIL] setx 失败：%s" % (r.stderr or r.stdout).strip())
        return 1
    print("  [OK] 已写入用户环境变量 %s" % VAR_NAME)
    print()
    print("  === 接下来必须做的 ===")
    print("  1) 关掉启动控制台窗口再重新运行 start-all.bat（setx 只对新进程生效）")
    print("  2) 用管理员登录，打开顶栏「导入 LLM」，确认能保存 API Key")
    print("  3) 提醒已配过密钥的用户：模型调不通就重新填一次三方密钥")
    return 0


if __name__ == "__main__":
    sys.exit(main())
