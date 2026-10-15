#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
LLM 密钥加密凭据（TCM_LLM_ENC_KEY / TCM_LLM_ENC_SALT）生成/评估工具。

## 为什么需要它

「导入 LLM」把每个用户的三方 API Key 加密后落库（`user_llm_config.api_key`）。
加密实现是 spring-security-crypto 的 `Encryptors.delux(password, salt)`
（AES-256-GCM，密钥由口令 + 盐经 PBKDF2 派生，密文为 `hex(IV‖密文‖tag)`），
凭据取自 `TCM_LLM_ENC_KEY`（口令）与 `TCM_LLM_ENC_SALT`（盐，十六进制偶数长度）。

`application.yml` 的 `llm.crypto-password` / `llm.crypto-salt` 里**已带开发用默认值**
（yml 写成 `${TCM_LLM_ENC_KEY:…}` 占位符，所以环境变量优先级天然更高），
本地拉下代码就能用、不会出现「未配置 LLM 加密密钥」。本脚本是**生产环境**该做的那一步：
把默认值换成随机值。

## 用法

    # 1) 演练（默认）：评估现状 + 打印将生成的凭据与要执行的命令，不改任何东西
    python tools/init-llm-enc-key.py

    # 2) 真正设置：把新凭据写入当前用户的持久环境变量（Windows setx）
    python tools/init-llm-enc-key.py --apply

    # 3) 只打印凭据（自己决定怎么配，如写进 Docker/CI 的 secret）
    python tools/init-llm-enc-key.py --print-key

设置后**必须重启后端**（重新运行 start-all.bat）才生效：
`LlmSecretCipher` 在构造器里读配置，运行中改不会重新加载。
注意 `setx` 只对新开的进程生效 —— 重启后端前先关掉启动控制台窗口再重开。

## ⚠️ 换凭据的代价（先读这段再决定要不要 --apply）

GCM 密文绑定密钥。**换掉口令或盐之后，之前加密落库的 API Key 全部解不开**：
`LlmSecretCipher.decrypt()` 返回空串并告警一次，用户表现为「配置看着还在、
但模型调不通」，需要各自重填一次三方密钥。

所以在**已经有用户配过密钥**的环境上换凭据是一次有损操作，要挑时间窗做。
"""
import argparse
import base64
import os
import secrets
import shutil
import subprocess
import sys

KEY_VAR = "TCM_LLM_ENC_KEY"
SALT_VAR = "TCM_LLM_ENC_SALT"
KEY_BYTES = 32          # 口令长度不做校验，这里仍按 32 字节随机生成（Base64 后 44 字符）
SALT_BYTES = 8          # 盐：8 字节 → 16 个 hex 字符（Encryptors 要求偶数长度 hex）
YML = os.path.join("java-backend", "src", "main", "resources", "application.yml")


def make_key():
    """生成 32 字节随机口令的 Base64 表示（44 字符，含尾部 =）。"""
    return base64.b64encode(secrets.token_bytes(KEY_BYTES)).decode("ascii")


def make_salt():
    """生成 16 个十六进制字符的盐（8 字节）。"""
    return secrets.token_hex(SALT_BYTES)


def decode_len(b64):
    """返回 Base64 解码后的字节数；非法 Base64 返回 None。"""
    try:
        return len(base64.b64decode(b64, validate=True))
    except Exception:
        return None


def is_even_hex(s):
    """盐必须是偶数长度的十六进制串（Encryptors 的硬约束）。"""
    if not s or len(s) % 2 != 0:
        return False
    try:
        bytes.fromhex(s)
        return True
    except ValueError:
        return False


def yml_has_default():
    """yml 里是否已带开发默认口令（即「开箱可用」是否成立）。"""
    if not os.path.exists(YML):
        return None
    with open(YML, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if s.startswith("crypto-password:") or s.startswith("# crypto-password:"):
                return not s.startswith("#")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="把新凭据写入当前用户的持久环境变量（Windows setx）")
    ap.add_argument("--print-key", action="store_true",
                    help="只打印新凭据，不做任何改动")
    args = ap.parse_args()

    env_key = os.environ.get(KEY_VAR)
    env_salt = os.environ.get(SALT_VAR)
    default_on = yml_has_default()

    print("  === 现状 ===")
    print("  环境变量 %s：%s"
          % (KEY_VAR, ("已设置（%d 字符）" % len(env_key)) if env_key else "未设置"))
    print("  环境变量 %s：%s"
          % (SALT_VAR,
             ("已设置（%d 字符）%s" % (len(env_salt), "" if is_even_hex(env_salt) else " **不是偶数长度 hex**"))
             if env_salt else "未设置"))
    if default_on is None:
        print("  [警告] 找不到 %s（请在仓库根目录运行）" % YML)
    elif default_on:
        print("  application.yml：已带开发默认口令与盐 → 当前**开箱即可用**，不会报「未配置 LLM 加密密钥」")
    else:
        print("  application.yml：**没有**开发默认值，且环境变量未设置 → 保存密钥会被拒（503/1011）")

    key = make_key()
    salt = make_salt()
    print()
    print("  === 新生成的凭据（生产用）===")
    print("  %s = %s" % (KEY_VAR, key))
    print("    · Base64 解码后 %d 字节" % decode_len(key))
    print("  %s = %s" % (SALT_VAR, salt))
    print("    · %d 个十六进制字符（%d 字节），满足「偶数长度 hex」" % (len(salt), SALT_BYTES))

    if args.print_key:
        print()
        print("  === 只打印模式，未做任何改动 ===")
        return 0

    print()
    print("  === 影响（重要）===")
    print("  · 换凭据后，**已加密落库的 API Key 全部解不开**，用户需要各自重填一次")
    print("  · 必须重启后端才生效（构造器读配置，运行中改不重新加载）")
    print("  · setx 只对新开的进程生效：重开启动控制台窗口，再跑 start-all.bat")

    if not args.apply:
        print()
        print("  === 演练模式，未改动任何东西 ===")
        print("  确认后再执行：python tools/init-llm-enc-key.py --apply")
        print("  或只取凭据自己配：python tools/init-llm-enc-key.py --print-key")
        return 0

    if not shutil.which("setx"):
        print()
        print("  [FAIL] 本机找不到 setx（非 Windows？）—— 请手工设置环境变量：")
        print("         %s=%s" % (KEY_VAR, key))
        print("         %s=%s" % (SALT_VAR, salt))
        return 1

    # setx 的值上限 1024 字符，这里 44 / 16 字符远小于限制；失败会以非 0 退出码体现
    for var, value in ((KEY_VAR, key), (SALT_VAR, salt)):
        r = subprocess.run(["setx", var, value], capture_output=True, text=True)
        if r.returncode != 0:
            print("  [FAIL] setx %s 失败：%s" % (var, (r.stderr or r.stdout).strip()))
            return 1
        print("  [OK] 已写入用户环境变量 %s" % var)
    print()
    print("  === 接下来必须做的 ===")
    print("  1) 关掉启动控制台窗口再重新运行 start-all.bat（setx 只对新进程生效）")
    print("  2) 用管理员登录，打开顶栏「导入 LLM」，确认能保存 API Key")
    print("  3) 提醒已配过密钥的用户：模型调不通就重新填一次三方密钥")
    return 0


if __name__ == "__main__":
    sys.exit(main())
