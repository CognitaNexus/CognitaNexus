"""一次性校验：用 Python 复刻 Java 分类器逻辑，跑全部真实成果，看词表覆盖与分布是否合理。
不是交付物，只是写 Java 之前用来判断词表质量的探针。"""
import json, sys, collections, re

TAX_PATH = r'D:\code\academicplatform\services\core-service\src\main\resources\taxonomy\academic-fields.json'
tax = json.load(open(TAX_PATH, encoding='utf-8'))
MIN = tax['minScore']; RATIO = tax['secondaryRatio']
DW = tax['titleWeight']; AW = tax['abstractWeight']
SAT = 3.0
CJK = re.compile(r'[\u4e00-\u9fff]')

def normalize(raw):
    out = [' ']
    pending = False
    for ch in (raw or '').lower():
        keep = ch.isdigit() or ('a' <= ch <= 'z') or bool(CJK.match(ch))
        if keep:
            if pending:
                out.append(' '); pending = False
            out.append(ch)
        else:
            pending = True
    out.append(' ')
    return ''.join(out)

FIELDS = []
for f in tax['fields']:
    terms = []
    for t in f['terms']:
        norm = normalize(t['term']).strip()
        needle = ' ' + norm if norm.isascii() else norm
        terms.append((t['term'], needle, float(t['weight'])))
    FIELDS.append((f['canonicalName'], terms))

def classify(title, venue, abstract):
    dec = normalize((title or '') + ' ' + (venue or ''))
    body = normalize(abstract)
    dec_empty = not dec.strip(); body_empty = not body.strip()
    scored = []
    ev = {}
    for name, terms in FIELDS:
        s = 0.0; hits = []
        for text, needle, w in terms:
            ind = (not dec_empty) and needle in dec
            if not ind and (body_empty or needle not in body):
                continue
            eff = w * (DW if ind else AW)
            s += eff
            hits.append((f"{'title' if ind else 'abstract'}:{text}={eff}", eff))
        if s > 0:
            scored.append((name, round(s, 3)))
            ev[name] = hits
    if not scored:
        return [], {}
    scored.sort(key=lambda x: (-x[1], x[0]))
    top = scored[0]
    if top[1] < MIN:
        return [], ev
    runner = scored[1][1] if len(scored) > 1 else 0.0
    out = [top[0]]
    if len(scored) > 1 and runner >= max(MIN, top[1] * RATIO):
        out.append(scored[1][0])
    return out, ev

def confidence(score, other):
    strength = score / (score + SAT)
    dom = score / (score + other)
    return round(min(0.99, strength * (0.5 + 0.5 * dom)), 3)

rows = []
with open(sys.argv[1], encoding='utf-8') as fh:
    for line in fh:
        parts = line.rstrip('\n').split('|')
        if len(parts) >= 4:
            rows.append(parts[:4])

counter = collections.Counter(); withmulti = 0; none = 0; n = 0
samples = collections.defaultdict(list); conf = []
for rid, title, venue, abstract in rows:
    n += 1
    got, ev = classify(title, venue, abstract)
    if not got:
        none += 1
        if len(samples['(判不出)']) < 8:
            samples['(判不出)'].append(f'{title[:72]} || {venue[:30]}')
        continue
    for g in got:
        counter[g] += 1
    conf.append(confidence(ev and 0 or 0, 0) if False else None)
    if len(got) > 1:
        withmulti += 1
    if len(samples[got[0]]) < 3:
        samples[got[0]].append(f'{title[:70]} || {venue[:28]}')

print(f'总条数={n}  判不出领域={none} ({none/n:.1%})  多领域={withmulti} ({withmulti/n:.1%})')
print('--- 领域分布（按成果计，含多归属） ---')
for name, c in counter.most_common():
    print(f'  {name:<18} {c:>6}  {c/n:>6.1%}')
print(f'--- 样例（判不出只列前 8） ---')
for name, _ in counter.most_common():
    print(f'[{name}]')
    for s in samples[name]:
        print('   ', s)
if samples['(判不出)']:
    print('[判不出]')
    for s in samples['(判不出)']:
        print('   ', s)
