#!/usr/bin/env bash
# Build only into a caller-selected temporary directory; never replace a delivery.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="${1:?Usage: bash scripts/docs/build_current_word.sh /tmp/your-new-directory}"
case "$out" in /tmp/*) ;; *) echo 'Output must be a new directory under /tmp' >&2; exit 2;; esac
mkdir "$out"
# Render only temporary copies, so local builds do not depend on rsvg-convert.
for source in docs/uml/analytics/DGM-ACTIVITY-03-collab-path.puml docs/uml/usecase/overall-usecase.puml; do
  stem="$(basename "$source" .puml)"
  cp "$source" "$out/$stem.puml"
  env -u DISPLAY plantuml -charset UTF-8 -tpng -failfast2 "$out/$stem.puml"
done
export COGNITA_WORD_IMAGES="$out"
cat > "$out/fit-uml.lua" <<'LUA'
function Image(el)
  local stem = el.src:match('([^/]+)%.svg$')
  if stem then
    el.src = os.getenv('COGNITA_WORD_IMAGES') .. '/' .. stem .. '.png'
  end
  if el.src:match('DGM%-ACTIVITY%-03%-collab%-path%.png') then
    el.attributes['height'] = '18cm'
    el.attributes['width'] = '10.1cm'
  end
  return el
end
LUA
pandoc --resource-path=docs/srs:docs:. --lua-filter="$out/fit-uml.lua" -f gfm -t docx -s docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md -o "$out/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx"
{ pandoc --version; env -u DISPLAY plantuml -version; } > "$out/tool-versions.txt"

python3 - "$out/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.docx" <<'PYQA'
import re, sys, zipfile, xml.etree.ElementTree as ET
from pathlib import Path
md = Path('docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md').read_text()
with zipfile.ZipFile(sys.argv[1]) as archive:
    assert archive.testzip() is None
    body = ''.join(ET.fromstring(archive.read('word/document.xml')).itertext())
    media = [p for p in archive.namelist() if p.startswith('word/media/')]
ids = lambda text: set(re.findall(r'AC-IMPL-(?:FR|NF|IN)\d{2}-\d{2}', text))
assert len(ids(body)) == 46 and ids(body) == ids(md)
assert len(media) == len(re.findall(r'!\[', md)) == 2, media
print('PASS temporary Word ZIP/XML, 46 AC, 2 embedded images')
PYQA
