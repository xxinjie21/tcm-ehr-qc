#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
JWT secret 轮换工具（批次 17）。

## 为什么需要它

`java-backend/src/main/resources/application.yml` 里的 jwt.secret 是明文入库的开发占位值
（`tcm-ehr-qc-jwt-secret-key-2026-course-design`，含 "course-design" 字样、可猜、且与代码同仓）。
生产环境必须换成随机值，并且**定期轮换**。

## 执行轮换会发生什么（先读这段再动手）

- **所有已签发的 token 立即失效**：包括所有在线用户的登录态。他们下一次请求会拿到 401，
  需要重新登录。没有双密钥过渡窗口 —— 本脚本不实现「新旧密钥并存验证」，
  那需要改 JwtUtil 支持多密钥并加过期时间，属于另一个量级的改动。
- 因此**必须在可通知用户的时间窗内执行**（例如低峰期 + 提前公告），不要在做演示时执行。

## 用法

    # 1) 演练（默认）：只评估现有密钥强度并打印建议，不改任何文件
    python tools/rotate-jwt-secret.py

    # 2) 真正执行：写入新密钥（自动备份原文件为 application.yml.bak-<时间戳>）
    python tools/rotate-jwt-secret.py --apply

    # 3) 回滚：把备份复制回去，然后重启后端
    #    （备份文件名会打印出来）

执行后**必须重启后端**才生效：Spring 在启动时读取该配置，运行中改文件不会重新加载。

## 建议

- 轮换后立刻用管理员登录一次，确认服务正常（脚本会打印这条提醒）。
- 生产环境更推荐把 secret 放到环境变量（`JWT_SECRET`）而不是文件里，
  再用 `secret: ${JWT_SECRET}` 引用 —— 那样轮换不需要改仓库文件。
"""
import argparse
import datetime
import os
import re
import secrets
import sys

YML = os.path.join("java-backend", "src", "main", "resources", "application.yml")
PLACEHOLDER_HINTS = ("course-design", "secret-key", "changeme", "test", "example", "123456")


def read_yml(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def current_secret(text):
    m = re.search(r"^(\s*)secret:\s*(\S+)\s*$", text, re.MULTILINE)
    return (m.group(2), m.group(1)) if m else (None, None)


def assess(secret):
    """给出强度评估：长度、字符集、是否含占位词。"""
    notes = []
    if secret is None:
        return ["未找到 secret 配置"]
    if len(secret) < 32:
        notes.append("长度 %d < 32，偏短" % len(secret))
    else:
        notes.append("长度 %d，足够" % len(secret))
    kinds = sum([any(c.islower() for c in secret), any(c.isupper() for c in secret),
                 any(c.isdigit() for c in secret), any(not c.isalnum() for c in secret)])
    notes.append("字符类别 %d/4" % kinds)
    hit = [h for h in PLACEHOLDER_HINTS if h in secret.lower()]
    if hit:
        notes.append("含占位词 %s —— 这是开发占位值，应当更换" % hit)
    return notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="真正写入（不加则只演练，不改任何文件）")
    ap.add_argument("--length", type=int, default=48, help="新密钥长度，默认 48")
    args = ap.parse_args()

    if not os.path.exists(YML):
        print("  [FAIL] 找不到 %s（请在仓库根目录运行）" % YML)
        return 1
    text = read_yml(YML)
    old, indent = current_secret(text)

    print("  === 现状 ===")
    print("  文件：%s" % YML)
    print("  当前 secret：%s" % (old if old else "（未找到）"))
    for n in assess(old):
        print("    · %s" % n)

    new = secrets.token_urlsafe(args.length)
    print("  === 建议的新值 ===")
    print("  %s" % new)
    for n in assess(new):
        print("    · %s" % n)

    print("  === 影响（重要）===")
    print("  · 轮换后**所有已签发 token 立即失效**，在线用户需要重新登录")
    print("  · 必须重启后端才生效：配置在启动时读取，运行中改文件不会重新加载")
    print("  · 执行前请确认处于可通知用户的时间窗（避免演示/评审期间）")

    if not args.apply:
        print("  === 演练模式，未改动任何文件 ===")
        print("  确认影响后再执行：python tools/rotate-jwt-secret.py --apply")
        return 0

    if old is None:
        print("  [FAIL] 没有可替换的 secret 行，请手工确认配置格式")
        return 1
    bak = "%s.bak-%s" % (YML, datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    with open(bak, "w", encoding="utf-8") as f:
        f.write(text)
    text2 = re.sub(r"^(\s*)secret:\s*\S+\s*$",
                   lambda m: "%ssecret: %s" % (m.group(1), new), text, count=1, flags=re.MULTILINE)
    with open(YML, "w", encoding="utf-8") as f:
        f.write(text2)
    print("  [OK] 已写入新 secret")
    print("  备份：%s" % bak)
    print("  === 接下来必须做的 ===")
    print("  1) 重启后端（否则仍是旧密钥）")
    print("  2) 用管理员登录一次，确认服务正常")
    print("  3) 若需回滚：把 %s 复制回 %s 并再次重启" % (bak, YML))
    return 0


if __name__ == "__main__":
    sys.exit(main())
