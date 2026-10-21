#!/usr/bin/env python3
"""Build the complete Review Candidate from authoritative member modules.
Run from repository root. Standard library only; does not edit member files.
"""
from pathlib import Path
from collections import defaultdict
import re,json,hashlib
R=Path(__file__).resolve().parents[3];M=R/'docs/srs/modules';OUT=R/'docs/srs/CognitaNexus-SRS-V1.0.md'
names=['search-recommendation','collection-update','governance-dedup','scholar-disambiguation','scholar-claim','analytics-graph','admin-audit','nonfunctional']
owners=dict(zip(names,['01','02','03','03','04','05','06','06']))
acpat=r'AC-(?:FR|NF|IN|O)\d+-S\d+-\d+'
reg=json.loads((R/'docs/srs/global-id-registry.json').read_text())
texts={n:(M/(n+'.md')).read_text() for n in names};blocks=defaultdict(list);rest={};usecases={}
def rebase(s,p):
 def f(m):
  t=m[1]
  if '://' in t or t.startswith('#'):return m[0]
  t,a,*_=t.split('#')+[''];dest=(p.parent/t).resolve()
  try:rel=__import__('os').path.relpath(dest,OUT.parent)
  except ValueError:return m[0]
  return ']('+rel+('#'+a if a else '')+')'
 return re.sub(r'\]\(([^)]+)\)',f,s)
def clean(s):return s.strip().strip('-').strip()
for name,s in texts.items():
 lines=s.splitlines(keepends=True);used=set()
 for i,l in enumerate(lines):
  m=re.match(r'^(#{2,4}) (?:\d+(?:\.\d+)*\.? )?((?:FR|NF|IN|O)\d+)-S\d+',l)
  if not m:continue
  level=len(m[1]);j=i+1
  while j<len(lines):
   h=re.match(r'^(#{1,6}) ',lines[j])
   if h and len(h[1])<=level:break
   j+=1
  text=clean(''.join(lines[i:j]));used.update(range(i,j))
  title=re.sub(r'^#+ (?:\d+(?:\.\d+)*\.? )?','',lines[i].strip())
  body=''.join(lines[i+1:j]);body=re.sub(r'^#{4,6} ', '#### ',body,flags=re.M)
  # collection templates originally use bold labels; preserve content.
  blocks[m[2]].append((name,title,rebase(clean(body),M/(name+'.md'))))
 # Extract canonically owned full UC specs, not boundary notes from member03.
 for i,l in enumerate(lines):
  m=re.match(r'^(#{2,4}) UC-(\d+) ',l)
  if not m:continue
  level=len(m[1]);j=i+1
  while j<len(lines):
   h=re.match(r'^(#{1,6}) ',lines[j])
   if h and len(h[1])<=level:break
   j+=1
  id='UC-'+m[2];assert id not in usecases,id
  body=''.join(lines[i:j]);body=re.sub(r'^#+ (UC-\d+ .+)$',r'### \1',body,flags=re.M)
  body=re.sub(r'^#{4,6} ', '#### ',body,flags=re.M)
  usecases[id]=rebase(clean(body),M/(name+'.md'));used.update(range(i,j))
 rest[name]=rebase(''.join(l for i,l in enumerate(lines) if i not in used),M/(name+'.md'))
# governance usecases live in member03 review delivery
p=R/'docs/srs/review/member03-governance-usecases.md';s=p.read_text();ls=s.splitlines(keepends=True)
for i,l in enumerate(ls):
 m=re.match(r'^## \d+\. (UC-(?:09|10)) ',l)
 if not m:continue
 j=i+1
 while j<len(ls) and not ls[j].startswith('## '):j+=1
 t=''.join(ls[i:j]);t=re.sub(r'^## \d+\. ', '### ',t,count=1);t=re.sub(r'^### (?!UC-)', '#### ',t,flags=re.M)
 usecases[m[1]]=rebase(clean(t),p)
assert set(usecases)=={d['id'] for d in reg['usecases']},set(d['id'] for d in reg['usecases'])-set(usecases)
s='''# CognitaNexus 软件需求规格说明书

**版本：** V1.0 RC1 ｜ **日期：** 2026-10-12 ｜ **状态：** Review Candidate，待人工联合评审，未Frozen  
**项目：** 学术成果分享平台 ｜ **编制：** 六人责任分册联合整合，AI辅助整理 ｜ **审核与签认：** 待填写，未冒充六人共同批准

本规格覆盖学者中心的四类成果聚合、检索、门户认领与维护、数据治理、统计及合作关系探索。正文完整保留六人负责范围的子需求、关键流程、异常与验收条件；附录保留模块上下文、原章节定位和待决事项。本文可独立阅读，不以外部链接代替需求内容。候选规则未冻结前，相应AC不能判定通过。

## 1. 引言

### 1.1 文档目的

供课程需求建模评审、后续SPEC细化及测试追踪使用。需求完整性指已有责任分册完整纳入，不表示待决参数已确定或系统已实现。

### 1.2 系统范围

一期以学者为中心管理论文、专利、科研项目和获奖元数据，支持学者与成果双向发现和本人维护闭环。不包含投稿审稿、出版、全文托管与下载代理、付费交易、私信或学术质量认证。原站阅读／下载按原站权限完成，不保证每项有全文。

### 1.3 术语与缩写

SRS为需求规格，SPEC细化核心判定规则。账号独立于学者档案，成果实体独立于来源记录和参与关系；认领管理授权独立于本人确认与管理员审核。统一对象状态见第7章。原模块的A、J、Q、CD及BR引用须按责任来源解析；完整上下文已收入附录。

### 1.4 参考文档

课程任务书及课堂展示与提交要求；[V1.9需求基线](../requirements/学术成果分享平台需求分析与可行性报告.md)；[SRS编写与建模规范](CognitaNexus_SRS编写与需求建模规范_V1.0.md)；[核心SPEC](../spec/CognitaNexus-Core-SPEC-V1.0.md)。V1.9仍待小组评审，本次不修改其业务语义。

### 1.5 版本与责任

| 成员 | 负责范围 | 状态 |
| --- | --- | --- |
| 01 | FR01、FR02、O01、O02 | Review Candidate |
| 02 | FR08、FR10、FR16 | Review Candidate |
| 03 | FR09、FR15 | Review Candidate |
| 04 | FR03～FR07 | Review Candidate |
| 05 | FR11～FR13、IN01 | Review Candidate |
| 06 | FR14、NF01～NF08 | Review Candidate |

2026-10-12形成V1.0 RC1；组长确认DEC-01～03，授权编号整改与整合。未取得六人联合签认及另一子组共同评审，不标Frozen。

## 2. 系统总体说明

### 2.1 产品定位与边界

平台聚合已有成果，提供检索重定位、可认领门户、成果确认纠错及可信治理，不提供论文发表。外部来源通过获准API、文件导入或获准采集接入；来源更新不静默覆盖人工确认信息。

### 2.2 用户角色

访客可查询公开信息；登录用户可提交和查询本人认领申请；门户管理者只能维护获授权的具体门户；管理员在角色及对象权限内处理待办、配置与审计。系统自动处理和外部来源是相关参与方，不把内部服务实现画成用户角色。

### 2.3 运行场景与外部依赖

公开浏览无需登录；认领及维护需身份与对象授权。来源或外链失败时保留已收录元数据及更新时间。数据接入许可、可得字段及更新能力须逐源核验，平台不代替原站认证或付费。

### 2.4 约束与假设

FR01～FR16为Must，IN01为本期创新，O01/O02为Optional且不得成为Must或创新的隐式依赖。真实／模拟及来源证据显式区分；待审变更不覆盖公开版本。NF02 P95和NF05 60秒仍为待评审目标，测量条件未确定时不得声称验收通过。

## 3. 用例模型

### 3.1 参与者定义

参与者和权限按第2.2及第8章执行。门户管理者是特定门户授权身份，不等于所有学者或所有登录用户；推荐使用者本人仅在O02选择实施后适用。

### 3.2 系统总体用例图

![DGM-USECASE-01 系统总体用例图](../uml/usecase/generated/overall-usecase.svg)

### 3.3 用例清单

| 编号 | 用例 | 成员 | 对应父需求 |
| --- | --- | --- | --- |
'''
for d in reg['usecases']:s+=f"| {d['id']} | {d['name']} | {d['owner']} | {d['parents']} |\n"
s+='\n### 3.4 关键用例规格\n\n以下用例行为继承对应父需求，Optional仅在选择实施后适用。\n\n'+'\n\n'.join(usecases[id] for id in sorted(usecases))
s+='\n\n## 4. 功能需求规格\n\n各子需求保留成员原始FR／AC编号。原分册章节引用以附录对应成员上下文及需求编号定位；未决A／J／Q条件保留，不以候选结果替代人工决议。\n'
groups=[('4.1 检索与成果详情',['FR01','FR02']),('4.2 学者门户认领与维护',['FR03','FR04','FR05','FR06','FR07']),('4.3 数据采集治理与更新',['FR08','FR09','FR10']),('4.4 统计热点与专家网络',['FR11','FR12','FR13']),('4.5 管理后台与审计',['FR14']),('4.6 消歧归属与来源追踪',['FR15','FR16'])]
def reqs(parents):
 t=''
 for parent in parents:
  for name,title,body in blocks[parent]:
   t+=f'\n### {title}\n\n**责任来源：** 成员{owners[name]}，{name}分册。\n\n{body}\n'
 return t
for title,parents in groups:s+='\n### '+title+'\n'+reqs(parents)
s+='\n## 5. 创新与可选需求\n\nIN01属于本期创新；O01/O02保留Optional状态。\n'
for title,parents in [('5.1 IN01合作关系探索',['IN01']),('5.2 O01相似论文推荐',['O01']),('5.3 O02轻量个性化推荐',['O02'])]:s+='\n### '+title+'\n'+reqs(parents)
s+='\n## 6. 业务流程与状态模型\n\n图源为PlantUML，生成图仅为派生副本。CodeArts原生工程导入与导出未经实际平台验证，状态NOT VERIFIED。\n'
for title,id in [('6.1 门户认领审核活动图','DGM-ACTIVITY-01'),('6.2 数据采集任务状态图','DGM-STATE-01'),('6.3 认领申请状态图','DGM-STATE-02')]:
 d=next(d for d in reg['diagrams'] if d['id']==id);p=Path(d['source']);s+=f'\n### {title}\n\n![{id}](../uml/{p.parent.name}/generated/{p.stem}.svg)\n'
s+='\n### 6.4 补充模型\n\n统计生效状态仍为Q-2候选模型；时序图仅后续分析参考，不计作本阶段必交。\n'
for d in reg['diagrams']:
 if d['id'] in ['DGM-ACTIVITY-01','DGM-STATE-01','DGM-STATE-02','DGM-USECASE-01'] or d['stage']=='后续分析参考':continue
 p=Path(d['source']);s+=f'\n#### {d["id"]} {p.stem}\n\n![{d["id"]}](../uml/{p.parent.name}/generated/{p.stem}.svg)\n'
s+='\n## 7. 数据需求与业务规则\n\n### 7.1 核心业务对象与状态\n\n'
dec=(R/'docs/srs/review/integration-decisions-2026-10-12.md').read_text();a=dec.index('| 对象 |');b=dec.index('\n## 3.');s+=dec[a:b]+'\n\n### 7.2 字段语义与公开边界\n\n论文发表时间、专利申请／公开／授权时间、项目起止和获奖年份分别表达，不用采集时间冒充。保留角色、次序、原值、来源、更新时间和真实／模拟标识。专利申请人与发明人不混用；缺失标未知。分类型字段及原始表收入附录成员02／03上下文。\n\n### 7.3 去重规则\n\n稳定标识须同类型且证据相容；标题相似只产生候选，无标识须多证据核验。版本归并须明确依据，实体计数与出处数分开；人工结果不被自动更新覆盖。具体规则见本章规则注册和FR09。\n\n### 7.4 学者消歧与成果归属\n\n姓名相同不足以合并；身份和成果去重独立。认领只授予门户管理权，管理员审核独立留证，不生成本人确认。解除一条关系不删除全局成果及其他参与者关系。\n\n### 7.5 统计口径\n\nDEC-02：有效公开成果按去重实体计数；作者关系资格独立，待核验关系不成边或支持相应机构归属，不连带排除有效成果及其他合格关系。FR12可采用四类共同成果；IN01逐跳仅用共同论文。多机构分配、样本阈值和热点默认指标仍待评审。\n\n### 7.6 数据更新与人工修正\n\nDEC-01：范围读取结束且每条已读取记录有可追踪处置时可成功，人工审核可继续；披露未公开及待核验数。失败或停止保留成功历史；冲突不覆盖公开值；生效一致性按NF05待评审测量范围执行。\n\n### 7.7 全局业务规则\n\n以下为全局唯一语义摘要，分册细化与参数状态按附录保留。编号分配不构成新增功能承诺。\n\n| 编号 | 规则 | 责任来源 |\n| --- | --- | --- |\n'
for id,d in sorted(reg['rules'].items()):s+=f"| {id} | {d['text']} | {Path(d['source']).stem} |\n"
s+='\n## 8. 权限与安全需求\n\n### 8.1 访客权限\n\n仅访问允许公开的成果、学者和统计；不可查看审核附件、凭据、内部处理队列及他人偏好。\n\n### 8.2 登录用户权限\n\n可提交补充并查看本人申请；登录不获得任意门户修改权。\n\n### 8.3 门户管理权限\n\n只维护具体获授权门户；直接修改与需审核字段按待冻结白名单；待审共享字段不能覆盖公开版。\n\n### 8.4 管理员权限\n\n可处理授权范围内待办、配置和审计；不能用审核冒充本人确认、学术认证或未经决议的授权转移。\n\n### 8.5 对象级授权规则\n\n每次受控操作校验角色、账号状态和目标对象；失败不返回受控数据。权限变更生效后后续操作按新权限判定，NF05 60秒数据展示目标不构成权限撤销宽限。材料访问与清理周期保留待评审；详细矩阵及例外已收入附录各成员上下文。\n\n## 9. 非功能需求\n\nNF01～NF08保留原责任条款及AC。度量型模板仅作为Review Candidate变体，未假冒已批准NR-01。NF02/NF05测量条件须先确认；实现、覆盖率、集成及性能验收均为后续活动，当前未执行。\n'
s+=reqs([f'NF{i:02}' for i in range(1,9)])
s+='\n## 10. 验收与追踪\n\n### 10.1 验收场景与门槛\n\n覆盖公开检索、详情与原站、未认领门户、认领补充及通过／驳回、越权拒绝、成果纠错、重复导入、同名消歧、失败恢复、统计和逐跳论文路径。采用下表原AC及附录集成场景。依赖A／J／Q候选规则的条目在人工确认之前为BLOCKED，不按任一候选结果提前判通过。\n\n### 10.2 全量需求追踪矩阵\n\n下表按原子子需求列出原AC；原子需求共用模板时仍分别追踪。跨模块UC／BR／图详表保留在附录及来源追踪矩阵。\n\n| 子需求 | 责任成员 | 对应验收条件 | SPEC范围 |\n| --- | --- | --- | --- |\n'
allchildren={};allac=set()
for parent,bs in blocks.items():
 for name,title,body in bs:
  # the combined search blocks express ranges; AC identities are authoritative atomic membership.
  ac=set(re.findall(acpat,body));children=set(re.findall(r'((?:FR|NF|IN|O)\d+-S\d+)',title))|{x.rsplit('-',1)[0][3:] for x in ac}
  for c in children:
   # filter mention-only references from title and AC group
   if c.split('-')[0]!=parent:continue
   allchildren[c]=owners[name]
   cas=sorted(x for x in ac if x.startswith('AC-'+c+'-'));allac.update(cas)
   spec='核心SPEC：'+parent if parent in ['FR01','FR09','FR15'] else '按对应模块；非核心算法规范不强加'
   s+=f'| {c} | {owners[name]} | '+('、'.join(cas) if cas else '共享模板，详见该父需求AC')+f' | {spec} |\n'
# retain conditional AC outside a child block exactly once in the appendix, track it here too
for name,t in texts.items():
 for ac in set(re.findall(acpat,t))-allac:
  c=ac.rsplit('-',1)[0][3:]
  s+=f'| {c}（条件验收） | {owners[name]} | {ac} | 冻结前置见附录对应分册 |\n';allac.add(ac)
s+='\n### 10.3 决策未决项及签认\n\n'
a=dec.index('## 1.');s+=re.sub(r'^## ', '### ',dec[a:],flags=re.M)
s+='\n\n## 附录 责任分册上下文与追踪原表\n\n以下完整保留各分册在已提取需求和用例之外的内容，包括字段表、权限矩阵、异常说明、集成场景、原追踪、版本与待决项。原章节号仅用于责任分册内定位；需求本体已编排到第4／5／9章，用例已编排到第3章，不再重复定义。历史自检结论不是本次联合签认。\n'
for name,t in rest.items():
 s+=f'\n### 原分册 成员{owners[name]} {name}\n\n'
 # retain original headings as explicit scoped labels, all under appendix
 t=re.sub(r'^(#{1,6}) (.+)$',lambda m:'#### 原分册 '+m[2],t,flags=re.M)
 s+=t+'\n'
s+='\n### 成员02独立追踪矩阵\n\n'+rebase(re.sub(r'^#{1,3} (.+)$',r'#### 原追踪 \1',(M/'collection-update-traceability.md').read_text(),flags=re.M),M/'collection-update-traceability.md')
s+='\n### 全局编号与来源校验\n\n[UC／BR／DGM注册表](global-id-registry.md)及[旧号兼容映射](global-id-mapping.json)供维护追踪；本文已嵌入全部用例、需求和规则，不依赖该链接才能阅读。\n\n| 权威输入 | SHA256 |\n| --- | --- |\n'
for name,t in texts.items():s+=f'| {name}.md | {hashlib.sha256(t.encode()).hexdigest()} |\n'
OUT.write_text(s)
# exact token coverage includes conditional AC outside requirements
srcac=set().union(*(set(re.findall(acpat,t)) for t in texts.values()));outac=set(re.findall(acpat,s));assert outac==srcac,(srcac-outac,outac-srcac)
meta={'children':len(allchildren),'AC':len(srcac),'blocks':sum(map(len,blocks.values())),'usecases':len(usecases),'rules':len(reg['rules']),'diagrams':len(reg['diagrams']),'source_hashes':{name:hashlib.sha256(t.encode()).hexdigest() for name,t in texts.items()}}
(R/'docs/reports/srs/integration-coverage.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(meta,ensure_ascii=False))
