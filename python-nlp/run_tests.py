"""python-nlp 回归闸门 —— Java 侧 `mvn -o test` 的 Python 对等物。

跑法（仓库根目录或 python-nlp/ 均可）：
    python python-nlp/run_tests.py
    python-nlp/.venv/Scripts/python.exe python-nlp/run_tests.py

只跑**不依赖模型**的测试（不需要 model/ 那 422MB）：

    tests/test_batch20_rules.py   纯规则回归：舌象形态 / 苔藓黑名单 / 脉位分段 /
                                  词典精确命中 / 脉舌不在 symptoms
    tests/test_ner_chunking.py    分词与分窗：_pieces 拼接无损、_ner_windows 短文本单窗
                                  （monkeypatch nlp_model._ntokens，不加载模型）
    tests/test_noise_filter.py    产物过滤：单字残词 / 假药名形态 / 否定句阳性

**tests/test_extract.py 故意排除**：它是手动调试脚本，不是可自动化的测试 ——
0 个 assert、硬编码本机绝对路径 C:\\Users\\...\\Temp\\composed.txt、
且调 _load_model() 会吃掉 422MB 模型。留在闸门里只会让 CI 在别人机器上必挂。

退出码：全部通过 0，任一失败 1（可直接接 CI，也是「改完 python-nlp 得跑一遍」的入口）。
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# 从轻到重排序：纯规则在前，失败时先暴露最便宜的问题
TESTS = [
    os.path.join('tests', 'test_batch20_rules.py'),
    os.path.join('tests', 'test_ner_chunking.py'),
    os.path.join('tests', 'test_noise_filter.py'),
]


def main() -> int:
    failed = []
    for name in TESTS:
        path = os.path.join(HERE, name)
        print(f'\n===== {name} =====')
        # 用同一个解释器跑子进程，避免 PATH 上的 python 与当前 venv 不一致
        proc = subprocess.run([sys.executable, path], cwd=HERE)
        if proc.returncode == 0:
            print(f'----- {name} 通过')
        else:
            failed.append((name, proc.returncode))
            print(f'----- {name} 失败（退出码 {proc.returncode}）')

    print('\n' + '=' * 46)
    if failed:
        print(f'闸门失败：{len(failed)}/{len(TESTS)} 个测试文件未通过')
        for name, code in failed:
            print(f'  - {name}（退出码 {code}）')
        return 1
    print(f'闸门通过：{len(TESTS)}/{len(TESTS)} 个测试文件全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
