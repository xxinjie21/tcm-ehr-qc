# test_ner_chunking.py
import sys, os
ROOT = os.path.join(os.path.dirname(__file__), '..', 'python-nlp')
sys.path.insert(0, ROOT)

import main

# test _pieces
text = '舌质淡胖，边有齿痕，苔白腻。脉细数，左尺无力'
pieces = list(main._pieces(text))
assert ''.join(p[1] for p in pieces) == text
print('pieces ok')

# test short text window
orig_nt = main._ntokens
try:
    main._ntokens = lambda s: len(s)
    win = list(main._ner_windows('abc'))
    assert len(win) == 1 and win[0][1] == 'abc'
    # long text: budget 508 chars; 10 chars → 1
    win = list(main._ner_windows('1234567890'))
    assert len(win) == 1
    print('windows short ok')
finally:
    main._ntokens = orig_nt
