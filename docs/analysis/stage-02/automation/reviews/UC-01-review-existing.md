REVIEW_STATUS: PASS
SEVERITY: MINOR

# UC-01 独立审查（UC-01-review-existing）

**审查对象：** `docs/analysis/stage-02/uc-01/`（01–08、README、`diagrams/*.puml`、`diagrams/generated/*`）
**基线：** SRS V2.2 69 AC（`docs/srs/CognitaNexus-Shared-SRS-V2.2-69AC.md` FR01:87-99、FR03:113-125、NF01:408-412、NF02:414-420、§7 BR-IMP:371-383、§8:398、§12:502-510）、`AGENTS.md`、`automation/instructions/REVIEWER.md`、`docs/analysis/stage-01/01-教材方法映射.md`、`docs/analysis/stage-02/{02,03,05}-*.md`、`docs/uml/analysis/DGM-CLASS-01-analysis.puml`、`docs/uml/analysis/DGM-SEQ-03-search.puml`
**性质：** 只读静态审查；未修改 `uc-01/`、SRS、SPEC 或全局模型。不改 AC、不改全局图。
**本机限制：** 本次会话无 shell/无写入能力，未执行 `plantuml` 与 `scripts/docs/*.py`；脚本"真实输出"通过阅读脚本源码核对，SVG 通过读取文件核对。运行类验证仍为 NOT RUN。

## 1. 审查范围与读取文件

| 文件 | 读取 | 用途 |
|---|---|---|
| `uc-01/README.md`、`01-UC-01-use-case-spec.md` | 全文 | 事件流、前后置、备选、AC 覆盖 |
| `uc-01/02-event-flow-trace.md`、`03-bce-identification.md` | 全文 | 逐事件来源、BCE 推导 |
| `uc-01/04-sequence-messages.md`、`05-vopc.md` | 全文 | M1–M23 逐消息依据、VOPC 关系依据 |
| `uc-01/06-class-definition.md`、`07-global-model-diff.md`、`08-self-check.md` | 全文 | 职责/属性/关系、全局差异、验证声明 |
| `uc-01/diagrams/DGM-SEQ-06-search.puml`、`DGM-VOPC-02-search.puml` | 全文 | 实际图源（非摘要） |
| `diagrams/generated/*.svg` | 头部 | 导出物存在且为可解析 XML，标题与图源一致 |
| SRS FR01/FR03/NF01/NF02、BR-IMP 表、§8 权限表、§7.1 字段、§12 | 定向 | 需求语义与引用核对 |
| `docs/uml/analysis/DGM-CLASS-01-analysis.puml`、`DGM-SEQ-03-search.puml` | 全文/定向 | 行号引用核对、同名序列冲突核对 |
| `stage-02/03-AC-UC-分析模型追踪.md`、`02-用例模型与规约.md`、`05-分析序列与用例实现.md`、`stage-01/01-教材方法映射.md`、`automation/{analysis-class-registry,unresolved-decisions,review-summary}.md` | 定向 | 追踪一致性、待决项登记 |

## 2. 已核对无误的部分（含证据）

- **AC 覆盖完整且未编造**：`01:89-97` 逐条映射 `AC-IMPL-FR01-01/02/03`、`NF01-01`、`NF02-01/02`；追踪表 `03-AC-UC-…:51-53,109-111` 与反向表 `:125` 一致；无新增 AC。
- **事件流可追溯**：E1–E9（`02:15-27`）与 A1–A4（`02:29-36`）逐项给出"已证实/分析推导/待确认"性质；A4 明确标注 Q-03 而非写成已批准规则（`01:71`）。未纳入项（详情/主页/统计/网络/推荐）有归属说明（`02:50-59`），未把跨 UC 行为塞入本 UC。
- **BCE 由事件流得出，非照抄全局图**：4 类均有"发现事件 + 不能由其他候选承担"的证据（`03:15-22,24-49`），并显式拒绝 `SearchContext`/`SearchResult`/`SourceRecord`/`User`/`UserProfile`/`Claim`/`SearchIndex`（`03:51-62`）；`03:28` 的全局 `SearchUI` 行号引用正确（`DGM-CLASS-01-analysis.puml:17`）。
- **对象边界守住**：Scholar≠UserProfile（`03:64-72`、`06:59`，依据 FR03/SRS §12/BR-IMP-03）；WorkEntity≠SourceRecord/WorkVersion（`06:47`）；不触 FR12 四类边与 IN01 论文子图（`05:38-46`、`06:59`）。
- **逐消息可追溯**：M1–M23 全部有发送者/接收者/事件步骤/职责/依据（`04:20-46`）；空结果（M15/M16）与执行失败（M11/M12）分离，有专门说明（`04:70-75`）；只读，无写消息（`04:80-82`）。
- **VOPC 与顺序图一致**：VOPC 类集合恰为顺序图 4 个系统对象（`05:8-17,47-56`）；关系均可回溯至 M2/M7/M9（`05:19-26`）；多重性未无据强填并给出理由（`05:28-30`）；不建 Work—Scholar 关系并给出 AC-01 依据（`05:38-46`）。图源与文档一致（`DGM-VOPC-02-search.puml` 仅 3 条关系、图例声明"不定义持久多重性"）。
- **未引入实现设计**：无数据库/API/框架/缓存/实现类；索引与快照在产品级被明确排除（`03:60`、`05:30`、`06:75-84`、图例末段）。
- **"无权"处理的排除有基线支撑**：`01:96` 声明"无权不进入公开检索"，与 SRS §8 权限表一致（SRS:398 对"公开检索"四类角色均为"读"），未把"越权可分"强行搬到公开检索。
- **全局图行号引用准确**：`07:10-15,26-37` 引用的 `SearchUI` 第17行、`SearchCtl` 32–34、`Scholar` 58–61、`Work` 74–78、`SearchCtl ..> Work` 110，与 `DGM-CLASS-01-analysis.puml` 实际行号逐条相符；"未见 `SearchCtl→Scholar`"结论属实（全文件仅 `:97-98,110` 相关边）。
- **69 AC 未被改动**：`08:58` 引用的 `verify_69_ac.py` 输出串与脚本真实打印串逐字一致（`scripts/docs/verify_69_ac.py:113-114`）；工作区在 `docs/srs`、`docs/spec`、`docs/analysis/stage-01` 无改动，支持 `08:14` 的说法。
- **待决项未被伪装成已批准**：Q-01/Q-02/Q-03/Q-05（`01:99-106`、`08:94-105`）与 `automation/unresolved-decisions.md` D-01/D-02/D-03 对应，口径一致。

## 3. 问题表

| ID | 严重度 | 文件:行/图 | 证据 | 修改建议（最小可执行） |
|---|---|---|---|---|
| UC01-M01 | MINOR | `01-UC-01-use-case-spec.md:43-48,64-72,96`；`04-sequence-messages.md:20-46`；`DGM-SEQ-06-search.puml` | NF01 规范性原文含"加载…和处理中可区分；表单有字段提示"（SRS:410），但 UC-01 全文无"表单/字段提示/加载/处理中"任何表述（对 `uc-01/` 全目录检索该四项为 0 命中），顺序图也无"处理中"状态消息；`01:96` 只把 NF01 归到"空/失败"。本 UC 被追踪表列为 NF01 覆盖 UC（`03-AC-UC-…:37`），该两条规范句未覆盖也未登记排除。 | 在 `01 §7` 增 A5 或在 `04` 增一条消息"显示处理中/加载状态"，并在 `01 §5.1`/`§10` 补一句"表单字段提示由 SearchUI 承担"；若判定不属于分析层，则在 `08 §5` 增一条 Q 项显式登记排除与理由。不需改图以外内容。 |
| UC01-M02 | MINOR | `06-class-definition.md:33,45,57` | `searchableText`（"公开字段的可检索投影"）、`searchableIdentity`（"可检索投影"）、`readVersionRef`（"读取版本引用"）属检索索引/快照实现措辞；SRS §7.1（SRS:377）只定义成果业务字段，从未定义"可检索文本/版本引用"属性，且与 `README:32-36`"不把快照机制写成既定需求"存在张力（`06` 已注明"具体字段来自后续""机制待Q-02"，故为措辞级）。 | 改为业务名并给出处：如"公开标题/摘要等公开字段（组成见 SRS §7.1、FR02）"、"公开姓名/别名（FR03）"；`readVersionRef` 改为"上下文标识（机制待 Q-02）"或直接并入 Know 描述。 |
| UC01-M03 | MINOR | `06-class-definition.md:45`；`01-UC-01-use-case-spec.md:99-105`；`08-self-check.md:94-105` | FR01 要求"年份（两端含）"筛选（SRS:89），但 SRS §7.1 对四类成果的时间语义不同：论文"发表年份"、项目"起止时间"、获奖"年度"（SRS:377）。`06:45` 用单一 `year` 属性隐含"每成果一个年份"，等价于替未决的语义做了默认选择，而 Q 列表（`01 §11`、`08 §5`）未登记该项。 | 在 `01 §11` 与 `08 §5` 增 Q-09"成果'年份'在项目（起止）/获奖（年度）/专利（日期）取哪个语义，区间成果如何两端含"；在 `06:45` 依据列引用该 Q。属性本身可保留。 |
| UC01-M04 | MINOR | `08-self-check.md:66,71`（对照 `scripts/docs/verify_scoped_srs.py:72` 及文件末尾 `print(json.dumps(summary…))`、`scripts/docs/check_documentation.py:53-54`） | 两个脚本的真实 stdout 是 JSON（`status`/`authoritative_ac`/`resolved_relative_links`/`uml_svg_pairs`/`errors`），而 `08` 以 `DOCUMENT_STATIC_PASS_ONLY 69`、`STATIC_PASS_ONLY links=1245 svg=30 errors=0` 呈现在"实际执行的验证"代码块中，非逐字输出（`verify_69_ac.py` 那条则逐字一致）。`08:12` 的 HEAD 与 `08:74-92` 的渲染执行亦无法由审查方复核。 | 粘贴真实 JSON 摘要字段，或把代码块标注为"摘要（非逐字 stdout）"；HEAD 与渲染命令注明"操作者本机记录，未由审查复核"。附 §5 命令供复核。 |
| UC01-M05 | MINOR | `docs/uml/analysis/DGM-SEQ-03-search.puml:19,29`；`uc-01/07-global-model-diff.md:49-51`；`03-AC-UC-分析模型追踪.md:51-53`；`05-分析序列与用例实现.md:12` | 同一 UC 现并存两套检索序列：旧 `DGM-SEQ-03` 断言"固定公开许可上下文与数据快照"（`:19`）与"当前页结果 + 总数 + 条件回显"（`:29`），与 UC-01 的 A-01/Q-02（快照机制未冻结）和 Q-05（不要求总数）相反；追踪表同时列 `SEQ-03 / SEQ-06 / VOPC-02`，S02 序列索引 `05-分析序列…:12` 仍未列 SEQ-06/VOPC-02。`07 §5` 只提到旧模型串，未声明两图关系与取代。 | 在 `07` 增一小节"与 `DGM-SEQ-03` 的关系"（3–5 行）：SEQ-06 为 UC-01 权威序列；SEQ-03 的"快照/总数"表述待 R5 修订，本轮不改其源文件。并在 `automation/unresolved-decisions.md` 加一条 D 项。 |
| UC01-M06 | MINOR | `03-AC-UC-分析模型追踪.md:20,37,38`；`02-用例模型与规约.md:24` | 明细行已补 SEQ-06/VOPC-02（`:51-53,109-111`），但汇总表 FR01/NF01/NF02 行与 `02` 的 UC-01 模型列仍是旧模型串，与 `07 §5`"只改 UC-01 相关行"的口径不一致（同文件内自相矛盾）。 | 同步这 4 处单元格文本（各加 `/ SEQ-06 / VOPC-02` 或 `SEQ-06`），不改其他 UC 行。 |
| UC01-M07 | MINOR | `uc-01/diagrams/DGM-SEQ-06-search.puml`（图例第 2 行），已进入 `generated/DGM-SEQ-06-search.svg`/`.png` | 图例写"工作筛选只适用于成果"，"工作"是 `Work` 的直译，与全文"成果"、FR01 用语（SRS:89）不一致，属交付图面可见的措辞错误。 | 改为"成果专属筛选只适用于成果范围"，按 `08:74-92` 同一命令重渲染 SVG/PNG。 |
| UC01-M08 | MINOR | `08-self-check.md:20`（对照 `stage-01/01-教材方法映射.md:16`） | `08` 记"§5.4.3 p161–181、§5.4.4 p181–183"，S01 方法映射记"§5.4.3 p162起、§5.4.4 p181–183"；两处起点不一致（§5.4.2 在 S01 记 p155起，`08` 记 p155–161）。 | 采用 S01 页码口径（§5.4.3 p162起），或注明页码来源版本；不影响模型内容。 |

## 4. 无法确认的需求问题（不由审查方代为决定）

| 编号 | 问题 | 现状处理 | 需要谁决定 |
|---|---|---|---|
| UQ-1 | FR01"可检索当前公开有效的成果与学者"在 V2.2 的 User/Scholar 分离模型下，"学者"是否包含公开 `UserProfile` 个人主页，还是仅 `Scholar` 档案？UC-01 取后者（`03:64-72`、`06:50-60`，依据 FR03/§12）。 | 按"仅 Scholar"建模，未登记为待决项 | 负责人/联合评审（建议登记 D 项）；若含主页，UC-01 的参与类集合需增检查点 |
| UQ-2 | FR01 的"领域"与"发生时机构"未在 SRS §7.1 成果字段清单单列（SRS:377 仅列稳定ID/类型/标题/来源/时间/标识/生效状态，机构仅出现在项目与获奖描述中）。 | UC-01 作为 `WorkEntity.field`/`occurredInstitution` 从 FR01 筛选要求推导（`06:45`），未声称字段级来源 | R5 统一分析类时确认属性来源 |
| UQ-3 | NF02 的"单请求超时5秒"出自容量测试场景（SRS:416,488），UC-01 用作 A3 超时依据（`01:71`）。它是否等于产品级检索超时阈值？ | 已注明"来自NF02，但性能未实测" | 负责人；当前处理可接受，不应升级为已批准阈值 |
| UQ-4 | FR01 是否要求返回结果总数、分页控件形式？ | UC-01 按 Q-05 不返回总数（`08:104`） | 负责人确认；与 UQ-3 同属未冻结参数 |

## 5. 需由 supervisor 执行的验证命令（本审查未执行）

```bash
python3 scripts/docs/verify_69_ac.py
python3 scripts/docs/verify_scoped_srs.py
python3 scripts/docs/check_documentation.py
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-SEQ-06-search.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-SEQ-06-search.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-VOPC-02-search.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-01/diagrams/DGM-VOPC-02-search.puml

## 6. 结论

```text
AUTO_REVIEW_PASS
MAJOR/CRITICAL: 0
MINOR: 8（UC01-M01 … UC01-M08）
PENDING_DECISION: UQ-1 … UQ-4（负责人确认，不阻塞本 UC 分析）
RUNTIME/HUMAN: NOT RUN / 未签认
