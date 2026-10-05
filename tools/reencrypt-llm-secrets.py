#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""LLM 密钥重加密工具（批次 8 交付物）。

用途
----
更换 ``TCM_LLM_ENC_KEY``（或 ``llm.crypto-key``）时，把 ``user_llm_config.api_key``
里用**旧密钥**加密的密文，重新用**新密钥**加密一遍，避免用户被迫逐个重填。

密文格式（必须与 ``LlmSecretCipher`` 完全一致，勿改）
---------------------------------------------------
``Base64( IV(12 字节) ‖ AES-256-GCM(明文) ‖ tag(16 字节) )``；
密钥 = Base64 解出的 32 字节。

安全口径
--------
* **默认干跑**：只统计「能解开多少 / 已迁移多少 / 解不开多少」，一个字节都不写库；
  确认无误后再加 ``--apply``。
* **可回滚**：``--backup old-keys.json`` 会把旧密文原样导出（换钥后旧密钥若仍在手上，
  可以据此写回）。
* **重跑安全**：先试旧密钥，失败再试**新密钥** —— 能解开说明这行上次已经迁移过，
  直接跳过。否则「执行到一半中断后再跑」会把已迁移的密钥当成坏数据清掉。
* **先验证再落库**：每行都用新密钥加密、再用新密钥解回、与明文逐字节比对，
  对不上就中止（绝不写半成品）。
* **解不开的行**按原文档口径标记「需重填」：``api_key`` 置 NULL，并在报告里列出 ``user_id``。
  应用侧对 NULL 的处理就是「未配置密钥」，与它解密失败时的行为一致。

用法
----
    # 1) 干跑（只看报告）
    python tools/reencrypt-llm-secrets.py --old-key <旧B64> --new-key <新B64> \
        --password 123456

    # 2) 备份 + 执行
    python tools/reencrypt-llm-secrets.py --old-key <旧B64> --new-key <新B64> \
        --password 123456 --apply --backup old-keys.json

密钥也可用环境变量传：``TCM_LLM_ENC_KEY_OLD`` / ``TCM_LLM_ENC_KEY_NEW``
（避免把密钥留在 shell 历史里）。
"""

import argparse
import base64
import json
import os
import sys

try:
    import pymysql
except ImportError:  # pragma: no cover
    sys.exit("缺少依赖 pymysql：pip install pymysql")

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

IV_LEN = 12
KEY_BITS = 256


def parse_key(raw, label):
    """Base64 → 32 字节；不合法直接退出（宁可不跑，也不要拿错密钥去解生产数据）"""
    if not raw:
        sys.exit(f"缺少{label}：用 --old-key/--new-key 或对应环境变量传入（32 字节 Base64）")
    try:
        key = base64.b64decode(raw.strip(), validate=True)
    except Exception as e:
        sys.exit(f"{label}不是合法 Base64：{e}")
    if len(key) * 8 != KEY_BITS:
        sys.exit(f"{label}解出 {len(key)} 字节，要求 32 字节（AES-256）")
    return key


def decrypt(key, cipher_text):
    """解不开返回 None（调用方据此判「需重填」或「已迁移」）"""
    try:
        blob = base64.b64decode(cipher_text, validate=True)
    except Exception:
        return None
    if len(blob) <= IV_LEN:
        return None
    iv, body = blob[:IV_LEN], blob[IV_LEN:]
    try:
        return AESGCM(key).decrypt(iv, body, None).decode("utf-8")
    except Exception:
        return None


def encrypt(key, plain):
    iv = os.urandom(IV_LEN)
    body = AESGCM(key).encrypt(iv, plain.encode("utf-8"), None)
    return base64.b64encode(iv + body).decode("ascii")


def main():
    ap = argparse.ArgumentParser(description="把 user_llm_config.api_key 从旧密钥重加密到新密钥")
    ap.add_argument("--old-key", default=os.getenv("TCM_LLM_ENC_KEY_OLD"), help="旧密钥（32 字节 Base64）")
    ap.add_argument("--new-key", default=os.getenv("TCM_LLM_ENC_KEY_NEW"), help="新密钥（32 字节 Base64）")
    ap.add_argument("--host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")))
    ap.add_argument("--user", default=os.getenv("MYSQL_USER", "root"))
    ap.add_argument("--password", default=os.getenv("MYSQL_PASSWORD", ""))
    ap.add_argument("--database", default=os.getenv("MYSQL_DATABASE", "tcm_ehr"))
    ap.add_argument("--apply", action="store_true", help="真正写库；不加则只干跑")
    ap.add_argument("--backup", help="把旧密文导出到该 JSON 文件（写库前执行）")
    args = ap.parse_args()

    old_key = parse_key(args.old_key, "旧密钥")
    new_key = parse_key(args.new_key, "新密钥")
    if old_key == new_key:
        sys.exit("新旧密钥相同：这次运行没有任何意义，请确认是否拿错了参数")

    conn = pymysql.connect(host=args.host, port=args.port, user=args.user,
                           password=args.password, database=args.database,
                           charset="utf8mb4", autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT user_id, api_key FROM user_llm_config "
                        "WHERE api_key IS NOT NULL AND api_key <> ''")
            rows = cur.fetchall()

        todo, already, broken, backup = [], [], [], {}
        for user_id, cipher_text in rows:
            backup[user_id] = cipher_text
            plain = decrypt(old_key, cipher_text)
            if plain is None:
                if decrypt(new_key, cipher_text) is not None:
                    already.append(user_id)      # 上次已迁移，重跑不该动它
                else:
                    broken.append(user_id)       # 两个密钥都解不开 = 真的需重填
                continue
            new_cipher = encrypt(new_key, plain)
            # 先验证再落库：解回来的必须与明文一致
            if decrypt(new_key, new_cipher) != plain:
                sys.exit(f"内部校验失败（user_id={user_id}）：加密后用新密钥解不回原文，已中止，未写任何数据")
            todo.append((user_id, new_cipher))

        print(f"[重加密] 待迁移 {len(todo)} 行 · 已是新密钥 {len(already)} 行 · 需重填 {len(broken)} 行")
        if already:
            print("  已迁移（跳过）：" + ", ".join(already))
        if broken:
            print("  需重填（api_key 置 NULL）：" + ", ".join(broken))

        if not args.apply:
            print("[重加密] 干跑结束，未写库。确认无误后加 --apply")
            return 0

        if args.backup:
            with open(args.backup, "w", encoding="utf-8") as f:
                json.dump(backup, f, ensure_ascii=False, indent=2)
            print(f"[重加密] 旧密文已备份到 {args.backup}")

        with conn.cursor() as cur:
            for user_id, new_cipher in todo:
                cur.execute("UPDATE user_llm_config SET api_key = %s WHERE user_id = %s",
                            (new_cipher, user_id))
            for user_id in broken:
                cur.execute("UPDATE user_llm_config SET api_key = NULL WHERE user_id = %s",
                            (user_id,))
        conn.commit()
        print(f"[重加密] 完成：迁移 {len(todo)} 行，标记需重填 {len(broken)} 行")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
