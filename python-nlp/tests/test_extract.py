import os
os.environ['TRANSFORMERS_OFFLINE']='1'
os.environ['HF_HUB_OFFLINE']='1'
from main import _load_model, extract, ExtractRequest
_load_model()
text = open(r'C:\Users\xxinj\AppData\Local\Temp\composed.txt', encoding='utf-8').read().strip()
resp = extract(ExtractRequest(text=text))
for k in ['diseases','symptoms','tongueList','pulseList','patternList','causeList','treatmentList','formulaList','herbs']:
    print(k, len(getattr(resp,k)))
print(resp.truncated)
