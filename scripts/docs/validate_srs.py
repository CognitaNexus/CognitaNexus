#!/usr/bin/env python3
"""Static document gates. Does not claim human approval or executable acceptance."""
from pathlib import Path
from collections import Counter
import json,re,zipfile,xml.etree.ElementTree as E,subprocess,os
R=Path(__file__).resolve().parents[2];D=R/'docs';S=D/'srs';reg=json.loads((S/'global-id-registry.json').read_text());report={}
acpat=r'AC-(?:FR|NF|IN|O)\d+-S\d+-\d+'
def acdefs(t):return re.findall(r'^\s*-\s*(?:\*\*)?('+acpat+r')(?:（[^\n]+?）)?[：:]',t,re.M)
mods=[p for p in (S/'modules').glob('*.md') if p.stem not in ['README','collection-update-traceability']]
sourceac=set().union(*(set(re.findall(acpat,p.read_text())) for p in mods));text=(S/'CognitaNexus-SRS-V1.0.md').read_text();defs=acdefs(text);co=Counter(defs)
report['AC']={'status':'PASS' if set(defs)==sourceac and all(n==1 for n in co.values()) else 'FAIL','source_unique':len(sourceac),'common_definitions':len(defs),'duplicates':[x for x,n in co.items() if n>1],'missing':sorted(sourceac-set(defs))}
coverage=json.loads((D/'reports/srs/integration-coverage.json').read_text());parents=set(re.findall(r'^\| ((?:FR|NF|IN|O)\d+)-S\d+',text,re.M));expected={f'FR{i:02}' for i in range(1,17)}|{f'NF{i:02}' for i in range(1,9)}|{'IN01','O01','O02'}
report['coverage']={'status':'PASS' if expected<=parents and coverage['children']==179 else 'FAIL','children':coverage['children'],'missing_parents':sorted(expected-parents)}
for kind,items in [('UC',[x['id'] for x in reg['usecases']]),('BR',list(reg['rules'])),('DGM',[x['id'] for x in reg['diagrams']])]:
 report[kind+'_registry']={'status':'PASS' if len(items)==len(set(items)) else 'FAIL','count':len(items)}
ucs=re.findall(r'^### (UC-\d+) ',text,re.M);report['UC_definitions']={'status':'PASS' if len(ucs)==21 and len(set(ucs))==21 else 'FAIL','count':len(ucs)}
links=[]
for p in D.rglob('*.md'):
 if 'incoming' in p.parts:continue
 for i,l in enumerate(p.read_text().splitlines(),1):
  for t in re.findall(r'\]\(([^)]+)\)',l):
   if '://' in t or t.startswith('#'):continue
   if not (p.parent/t.split('#')[0]).exists():links.append({'file':str(p.relative_to(R)),'line':i,'target':t})
report['links']={'status':'PASS' if not links else 'FAIL','broken':links,'scope':'Markdown文件目标；未验证外部URL或中文自动锚点'}
unknown=[];validbr=set(reg['rules']);validuc={x['id'] for x in reg['usecases']};validdg={x['id'] for x in reg['diagrams']}
for p in mods+list((S/'review').glob('*.md'))+list((D/'uml').rglob('*.puml'))+[D/'spec/CognitaNexus-Core-SPEC-V1.0.md']:
 for i,l in enumerate(p.read_text().splitlines(),1):
  # Old local numbers only allowed in explicit history/mapping explanations.
  if any(w in l for w in ['原稿 BR-','原稿BR-','旧局部','兼容映射','原编号分配建议']):continue
  for pat,valid in [(r'BR-\d+\b',validbr),(r'UC-\d+\b',validuc),(r'DGM-(?:USECASE|ACTIVITY|STATE|SEQ)-\d+',validdg)]:
   bad=set(re.findall(pat,l))-valid
   if bad:unknown.append({'file':str(p.relative_to(R)),'line':i,'ids':sorted(bad)})
report['references']={'status':'PASS' if not unknown else 'FAIL','unregistered':unknown}
ps=sorted((D/'uml').rglob('*.puml'));env=os.environ.copy();env.pop('DISPLAY',None);r=subprocess.run(['plantuml','-charset','UTF-8','-checkonly',*[str(p) for p in ps]],env=env,capture_output=True,text=True)
errors=[]
for p in ps:
 svg=p.parent/'generated'/(p.stem+'.svg')
 try:
  E.parse(svg)
  if 'Syntax Error' in svg.read_text():errors.append(str(svg))
 except Exception as ex:errors.append(str(ex))
report['UML']={'status':'PASS' if r.returncode==0 and not errors else 'FAIL','sources':len(ps),'generated_svg':len(ps)-len(errors),'errors':errors,'stderr':r.stderr}
for stem in ['srs/CognitaNexus-SRS-V1.0','spec/CognitaNexus-Core-SPEC-V1.0']:
 path=D/(stem+'.docx');md=D/(stem+'.md')
 with zipfile.ZipFile(path) as z:
  bad=z.testzip();rt=E.fromstring(z.read('word/document.xml'));body=''.join(rt.itertext());mdac=set(re.findall(acpat,md.read_text()));docac=set(re.findall(acpat,body))
  report[stem+'_Word']={'status':'PASS' if bad is None and mdac<=docac else 'FAIL','zip_error':bad,'missing_AC':sorted(mdac-docac),'image_count':sum(x.startswith('word/media/') for x in z.namelist()),'body_chars':len(body),'scope':'ZIP、XML及AC标识完整性；视觉检查另记'}
report['NF05']={'status':'PASS' if 'When 检查系统 When Then' not in text else 'FAIL'}
report['malformed_tokens']={'status':'FAIL' if re.search(r'UNF\d|DGM-DGM|UC-UNF',text) else 'PASS'}
report['SPEC_alignment']={'status':'PASS' if '参与关系统一为 `自动关联/本人确认/待核验/争议/已排除`' in (D/'spec/CognitaNexus-Core-SPEC-V1.0.md').read_text() else 'FAIL','scope':'公共对象状态、DEC-01～03与核心FR/AC追踪；候选算法参数未冻结'}
report['business_decisions']={'status':'BLOCKED','reason':'DEC-01～03有组长确认；其余A/J/Q参数及六人联合签认未完成，不能宣称业务验收或Frozen'}
report['CodeArts']={'status':'NOT VERIFIED','reason':'未实际操作平台验证原生工程导入、编辑及重新导出'}
report['runtime_tests']={'status':'NOT VERIFIED','reason':'需求建模整改，无实现代码；未执行算法单元、集成、性能及覆盖率测试'}
out=D/'reports/srs/integration-validation.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(1 if any(x['status']=='FAIL' for x in report.values()) else 0)
