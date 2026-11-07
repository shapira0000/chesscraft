"""Summarizes javac errors from a Gradle log: python ports/errors.py <log> [skip-substring ...]"""
import re
import sys

lines = open(sys.argv[1], encoding='utf-8', errors='replace').read().splitlines()
skip = sys.argv[2:]
seen = set()
for i, line in enumerate(lines):
    m = re.search(r'([A-Za-z0-9_]+\.java):(\d+): error: (.*)', line)
    if not m:
        continue
    code = lines[i + 1].strip() if i + 1 < len(lines) else ''
    sym = ''
    for j in range(i + 1, min(i + 6, len(lines))):
        if 'symbol:' in lines[j]:
            sym = lines[j].strip()
            break
    if any(s in code for s in skip):
        continue
    key = (m.group(3), code, sym)
    if key in seen:
        continue
    seen.add(key)
    print(f'{m.group(1)}:{m.group(2)} {m.group(3)} | {code[:120]} | {sym}')
