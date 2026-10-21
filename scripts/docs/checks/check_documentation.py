#!/usr/bin/env python3
"""Read-only documentation checks; JSON to stdout, never edits tracked assets."""
import json
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[3]
errors, local_sources, links = [], [], 0
for path in sorted((ROOT / 'docs').rglob('*.md')) + [ROOT/'README.md', ROOT/'AGENTS.md']:
    if 'incoming' in path.parts:
        continue
    text = re.sub(r'```.*?```', '', path.read_text(), flags=re.S)
    for target in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', text):
        target = unquote(target.split('#', 1)[0].strip('<>'))
        if not target or re.match(r'^[a-zA-Z]+:', target):
            continue
        links += 1
        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            errors.append(f'{path.relative_to(ROOT)}: missing {target}')
        elif 'incoming' in resolved.parts or '.local' in resolved.parts:
            local_sources.append(f'{path.relative_to(ROOT)}: local-only {target}')
svg_count = 0
for path in sorted((ROOT/'docs/uml').rglob('*.puml')):
    svg = path.parent/'generated'/path.with_suffix('.svg').name
    try:
        ET.parse(svg)
        if 'Syntax Error' in svg.read_text():
            raise ValueError('PlantUML error image')
        svg_count += 1
    except (OSError, ET.ParseError, ValueError) as exc:
        errors.append(f'{svg.relative_to(ROOT)}: {exc}')
word = {}
for md in sorted((ROOT/'docs').rglob('*.md')):
    doc = md.with_suffix('.docx')
    if 'incoming' in md.parts or not doc.exists():
        continue
    with zipfile.ZipFile(doc) as archive:
        if archive.testzip():
            errors.append(f'{doc}: invalid ZIP')
        xml = ET.fromstring(archive.read('word/document.xml'))
        body = ''.join(xml.itertext())
        ids = lambda t: set(re.findall(r'AC-(?:IMPL-)?(?:FR|NF|IN|O)\d{2}-(?:S\d{2}-)?\d{2}', t))
        missing = sorted(ids(md.read_text())-ids(body))
        word[str(doc.relative_to(ROOT))] = {'missing_source_ac': missing, 'media': sum(n.startswith('word/media/') for n in archive.namelist()), 'source_alignment_notice_missing': '工程范围对齐通知' in md.read_text() and '工程范围对齐通知' not in body, 'scope': 'ZIP/XML/AC only; not full text or visual equivalence'}
        if missing:
            errors.append(f'{doc.relative_to(ROOT)}: missing AC {missing}')
result = {'status': 'FAIL' if errors else 'STATIC_PASS_ONLY', 'relative_links': links, 'uml_svg_pairs': svg_count, 'word': word, 'local_source_dependencies': local_sources, 'errors': errors, 'external_urls_and_anchors': 'NOT_CHECKED', 'human_approval': 'NOT_VERIFIED', 'runtime_tests': 'NOT_EXECUTED'}
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(bool(errors))
