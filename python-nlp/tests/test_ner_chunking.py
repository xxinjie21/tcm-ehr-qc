# test_ner_chunking.py
import sys, os
ROOT = os.path.join(os.path.dirname(__file__), '..')
sys.path.insert(0, ROOT)

# _pieces / _ntokens / _ner_windows 自拆分后位于 nlp_model（main 只留 HTTP 层）。
# monkeypatch 必须打在 nlp_model 上：_ner_windows 内部按模块全局查 _ntokens，
# 改 main._ntokens 不会生效（main 只是 re-export 了函数对象本身）。
import nlp_model

# test _pieces
text = '舌质淡胖，边有齿痕，苔白腻。脉细数，左尺无力'
pieces = list(nlp_model._pieces(text))
assert ''.join(p[1] for p in pieces) == text
print('pieces ok')

# test short text window
orig_nt = nlp_model._ntokens
try:
    nlp_model._ntokens = lambda s: len(s)
    win = list(nlp_model._ner_windows('abc'))
    assert len(win) == 1 and win[0][1] == 'abc'
    # long text: budget 508 chars; 10 chars → 1
    win = list(nlp_model._ner_windows('1234567890'))
    assert len(win) == 1
    print('windows short ok')
finally:
    nlp_model._ntokens = orig_nt
