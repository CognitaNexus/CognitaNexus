# CognitaNexus 全局UC BR DGM编号注册表

**版本：** V1.0 RC1 ｜ **日期：** 2026-10-12 ｜ **状态：** Review Candidate，编号修复已执行，业务未Frozen。  
FR／NF／IN／O子需求和AC编号保持不变。相同业务约束可跨模块引用或复述，但编号只能解析为本表的一种语义和一个责任定义源。旧局部编号不是全局别名；必须连同来源模块使用映射。

## 1. 用例注册

| 全局编号 | 用例 | 责任成员 | 父需求 | 定义来源 |
| --- | --- | --- | --- | --- |
| UC-01 | 检索成果与学者 | 01 | FR01 | [search-recommendation.md](modules/search-recommendation.md) |
| UC-02 | 查看成果详情与来源 | 01 | FR02 | [search-recommendation.md](modules/search-recommendation.md) |
| UC-03 | 查看学者门户 | 04 | FR03 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-04 | 注册登录与账号权限 | 04 | FR04 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-05 | 提交补充并查看本人认领申请 | 04 | FR05 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-06 | 审核门户认领申请 | 04 | FR05 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-07 | 维护学者门户 | 04 | FR06 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-08 | 确认补录与纠正成果 | 04 | FR07 | [scholar-claim.md](modules/scholar-claim.md) |
| UC-09 | 处理成果清洗去重与治理异常 | 03 | FR09 | [member03-governance-usecases.md](review/member03-governance-usecases.md) |
| UC-10 | 核验学者身份与成果归属 | 03 | FR15 | [member03-governance-usecases.md](review/member03-governance-usecases.md) |
| UC-11 | 查看成果统计分析 | 05 | FR11 | [analytics-graph.md](modules/analytics-graph.md) |
| UC-12 | 查看机构分类排名 | 05 | FR11 | [analytics-graph.md](modules/analytics-graph.md) |
| UC-13 | 查看领域热点 | 05 | FR13 | [analytics-graph.md](modules/analytics-graph.md) |
| UC-14 | 查看基础专家合作网络 | 05 | FR12 | [analytics-graph.md](modules/analytics-graph.md) |
| UC-15 | 探索合作关系 | 05 | IN01 | [analytics-graph.md](modules/analytics-graph.md) |
| UC-16 | 查看四类成果与来源证据 | 02 | FR16 | [collection-update.md](modules/collection-update.md) |
| UC-17 | 管理后台待办权限与审计 | 06 | FR14 | [admin-audit.md](modules/admin-audit.md) |
| UC-18 | 管理数据来源与采集任务 | 02 | FR08 | [collection-update.md](modules/collection-update.md) |
| UC-19 | 更新来源数据并处理变更 | 02 | FR10 | [collection-update.md](modules/collection-update.md) |
| UC-20 | 查看相似论文推荐 | 01 | O01 | [search-recommendation.md](modules/search-recommendation.md) |
| UC-21 | 授权使用并清除个性化推荐偏好 | 01 | O02 | [search-recommendation.md](modules/search-recommendation.md) |

## 2. 业务规则注册

规则沿用各责任分册及组长确认的边界；分册中的候选数值、条件和状态对象不因登记编号而自动生效。

| 全局编号 | 唯一语义摘要 | 责任定义来源 |
| --- | --- | --- |
| BR-101 | 普通检索与详情不依赖 O01/O02 是否实施、是否授权或是否成功；推荐失败、行为记录失败不得使 FR01/FR02 不可用。个性化结果不能替代普通检索。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-102 | 检索、详情和推荐只提供平台已收录且允许展示的数据；采用生效的公开关系与版本，不泄露审核材料或未公开信息。来源允许展示且归属证据达到规则要求才公开，冲突进入核验。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-103 | 缺失字段不伪造，未知状态不升级为已确认；不得通过同名、相似标题、当前机构或其他不等价信息补造事实。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-104 | 外部原文不可访问不等于成果不存在。来源失效时保留元数据及更新时间；来源删除、撤稿、外链失败分别表达，一次失败不直接删除成果。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-105 | 审核通过、归属修正和数据更新后，展示与检索应采用已生效信息；正常情况下60秒内与门户、统计一致是 NF05 待评审目标，暂未生效或更新失败须提示。不得让待审版本覆盖公开内容。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-106 | “相似论文”仅表达内容／语义接近，不表示质量、权威性或平台认可研究结论；不以推荐结果直接作出去重合并或归属判定。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-107 | 个性化行为记录与使用须先有本人明确同意；仅处理推荐所需信息。用户可查看、清除本人偏好，清除成功后不得继续以已清除偏好生成后续推荐，不得以立即重建规避清除。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-108 | 统一入口检索成果和学者，结果范围可区分，不要求任何特定界面控件；未认领学者的公开档案仍可检索与访问。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-109 | 适用的类型、年份、领域、机构条件可组合；已接受条件不得静默丢弃，过滤可清除；同维度多选、时间语义与适用性按 A02、A03 冻结。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-110 | 分类浏览使用生效本地分类与来源映射，不能强行归类；允许一成果多领域，下级包含规则须明确；多个来源不生成重复成果结果。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-111 | 成果机构优先采用成果发生时单位，缺失标未知，不静默替换为当前任职机构；学者查询机构口径另按 A03 冻结。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-112 | 排序和分页不改变查询条件含义；固定数据与条件下遍历分页不重复、不遗漏实体；排序并列、缺失与分页边界按 A04 冻结。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-113 | 成功但无匹配与查询执行失败须区分；不自动放宽条件冒充原查询结果，不将旧结果伪装为本次查询成功结果。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-114 | 真实与模拟数据须可区分，模拟不得冒充真实；B §3.3.2 已要求支持过滤，但未指定普通用户搜索页控件或角色。过滤责任与验收必须在 A05 落定。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-115 | 四类成果展示公共与适用专属信息；字段有无、时间语义和参与角色分别表达；数据字段清单不是每条记录的完整性保证。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-116 | 成果与学者导航采用生效且允许公开的关系，不以同名直接关联；无学者关系时可以展示来源参与者名称但不伪造门户导航。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-117 | 来源可追溯，合并后保留的多个公开出处仍可查；时间类型不可混称，缺来源链接不能编造。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-118 | 不保证全文；原站登录、订阅及付费由原站处理；不代理认证、不托管全文、不提供下载代理，不把公开链接解释为可再分发许可。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-119 | 发表、版本、门户认领、学者确认、管理员审核分别表达；业务审核通过不是同行评审或学术质量认证；只有明确来源或审核证据才确认版本关系。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-120 | 引用量是可选信息；有多个来源时分别表达，不求和生成总引用量；项目经费不默认采集或公开。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-121 | O01 全部为 Optional，只针对论文详情；可基于标题、摘要、关键词、领域等内容，不要求论文关系图。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-122 | 相似结果排除当前成果实体本身，不因同一成果多来源而推荐自己；未归并版本及候选关系按 A10 处理，不新增去重规则。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-123 | 内容不足或无候选时不伪造推荐；推荐失败与无候选区分；结果仅引用已有且允许展示的信息。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-124 | O02 全部为 Optional；登录、认领、继续浏览、沉默都不是明确授权；授权不可确认时按未授权处理，未授权行为不得转用个性化。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-125 | 只使用本人授权范围内的近期检索、浏览、收藏等允许行为，不要求实施收藏或完整画像；具体范围与时限须冻结。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-126 | 兴趣以领域／关键词等业务含义及依据类型解释，不混入他人或未授权数据，不推断与推荐无关的敏感属性；数据不足不伪称已有兴趣。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-127 | 用户只能查看／清除本人偏好；清除失败不报告成功，清除与授权撤回语义分开；清除范围、在途结果和旧行为复用边界须按 A09 冻结。 | [search-recommendation.md](modules/search-recommendation.md) |
| BR-201 | 来源、获取范围与再展示须获准；停用／条件失效阻断新执行 | [collection-update.md](modules/collection-update.md) |
| BR-202 | 两个独立来源按提供者或独立集合；同源不同输入方式不重复计 | [collection-update.md](modules/collection-update.md) |
| BR-203 | 任务快照不可暗改，配置及关键操作记录操作者、时间和变化 | [collection-update.md](modules/collection-update.md) |
| BR-204 | 六状态、守卫、终态及停止／重试遵循§6.2 | [collection-update.md](modules/collection-update.md) |
| BR-205 | 失败／停止保留已完成结果，重放不重复成果、业务变更和同版本待办 | [collection-update.md](modules/collection-update.md) |
| BR-206 | 同源身份及规范化内容判新增／未变／变更，无身份只能候选；无游标说明范围与局限 | [collection-update.md](modules/collection-update.md) |
| BR-207 | 采集、检查、源更新时间、生效时间分别表达；缺失标未知 | [collection-update.md](modules/collection-update.md) |
| BR-208 | 不静默覆盖人工确认、排除和审核值；冲突保留原值，交审核；并发生效前复核 | [collection-update.md](modules/collection-update.md) |
| BR-209 | 四类均有样例，总量按去重实体≥10,000；保留出处，来源／版本不凑数；性质可核对 | [collection-update.md](modules/collection-update.md) |
| BR-210 | 正常生效结果按NF05在60秒内一致；待核验未生效不替换公开结果，失败有提示 | [collection-update.md](modules/collection-update.md) |
| BR-211 | 删除、撤稿、外链失败独立，有证据才变更来源状态，单次失败不删除全局成果 | [collection-update.md](modules/collection-update.md) |
| BR-212 | 类型字段、参与者角色与时间语义独立；未知不编造，不采集非公开敏感字段 | [collection-update.md](modules/collection-update.md) |
| BR-301 | 同类型稳定标识归一化后完全相等且证据相容，是自动关联既有成果的依据；无标识按FR09-S07的待评审组合条件处理 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-302 | 跨来源合并必须保留所有出处 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-303 | 无稳定标识时须比较年份、参与者、类型等多项证据 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-304 | 不同类型标识不得互相当作同一成果依据 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-305 | 标题相似只产生候选，不能单独作为合并依据 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-306 | 人工确认或已审核值优先于自动来源值，冲突不得静默覆盖 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-307 | 证据不足必须进入待核验，禁止自动合并 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-308 | 只有明确来源关系或经审核确认的匹配才能归并版本 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-309 | 不同版本不直接增加成果实体总量 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-310 | 合并／拆分必须可追溯，保留合并链 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-311 | 重复导入、重试与增量重叠不得增加实体或重复计数 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-321 | 缺失字段必须显式标明未知，禁止推断填充 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-322 | 时间精度与语义必须显式保存 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-323 | 无法可靠映射时标注未映射／待分类，禁止强行归类 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-324 | 已确认或已审核值不得被自动更新静默覆盖（公共规则） | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-325 | 来源原始值必须保留并可追溯到来源 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-326 | 来源删除、撤回、外链失效分别记录，均不删除成果 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-327 | 在NF05约定条件下按待评审60秒目标反映一致结果，失败给出提示（对应 NF05） | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-341 | 成果实体去重与学者身份合并相互独立，一方证据不足不得连带影响另一方 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-342 | 姓名相同不足以判定同一人 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-343 | 人员敏感信息不得作为公开消歧依据 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-344 | 机构归属优先采用成果发生时的单位，缺失标明未知 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-345 | 缩写、译名、相似形式只用于产生候选 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-346 | 满足条件（a）外部标识一致或（b）机构＋合作者＋领域支持才可自动合并 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-347 | 身份合并必须可追溯，合并后证据为并集 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-348 | 争议期间禁止自动合并与自动关联 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-349 | 冲突认领不得自动转移原授权；判定不自动改变门户管理权 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-350 | 门户认领状态与关系确认状态分别表达 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-351 | 认领通过、审核通过均不构成学术质量或真实性认证 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-352 | 参与关系必须记录角色与顺序，未知须显式标注 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-353 | 同一成果上不同参与者不得合并为一条关系，角色不得混用 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-354 | 关系建立必须有可追溯的来源依据 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-355 | 来源证据、本人确认、管理员审核是三类独立证据，不得合并为单一标签 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-356 | 关系状态五取一，优先级 已排除 > 争议 > 待核验 > 本人确认 > 自动关联 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-357 | 纠正后同时保留来源事实与人工修正结论 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-358 | 拆分后无法确定的归属必须置为待核验 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-359 | 解除个人关联只影响该条关系，不删除成果、出处或他人关系 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-360 | 治理结论生效后门户、检索与统计必须一致 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-361 | 归属争议期间禁止自动生成新结论 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-371 | 不采集手机号、私人邮箱及未公开项目细节作为消歧依据 | [scholar-disambiguation.md](modules/scholar-disambiguation.md) |
| BR-381 | 关键治理动作必须留痕 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-382 | 历史治理记录只追加不覆盖 | [governance-dedup.md](modules/governance-dedup.md) |
| BR-401 | 学者档案可先于账号存在 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-402 | 未认领公开门户无需登录可访问 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-403 | 门户认领、申请状态、对象授权、成果关系与审核来源分别表达；不得由驳回推导撤销别人授权 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-404 | 缺失字段标未知，不编造 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-405 | 说明已收录范围，不承诺全球全部成果 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-406 | 账号与学者独立，注册不要求已拥有账号 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-407 | 操作角色与具体对象授权同时满足才可执行 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-408 | 直接受保护请求必须鉴权，前端隐藏不能替代 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-409 | 账号状态与会话状态分离；禁用阻止受保护操作；额外状态与会话策略待定 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-410 | 认领审核通过仅授予该门户权，不代表法律确权或质量认证 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-411 | 冲突认领人工处理，不自动覆盖原授权；未解决不能普通批准 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-412 | 待审申请不改变门户公开内容或既有管理授权 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-413 | 本人可查申请状态、处理原因、结果及补充要求；审核记录可追溯 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-414 | 多证据人工审核；证据方式、字段与边界待冻结，学校邮箱／ORCID／主页／附件仅候选 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-415 | 需审核的门户修改通过前不替换公开内容；按冻结字段分类区分直接与待审 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-416 | 重要修改与审核保留操作者、时间、关键变化及结果 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-417 | 展示设置只控制允许范围，不能公开敏感材料或删除共享成果 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-418 | 维护操作持续检查目标授权，无目标权不得写入 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-419 | 本人确认只影响所选本人的关系；多人合法作者不是互斥冲突 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-420 | 需审核成果变更不提前覆盖公开值；无冲突本人确认不一律待审 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-421 | 排除一位学者关系不删全局成果、不改其他合法关系 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-422 | 成果操作结果、原因及生效情况可查询可追溯 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-423 | 同名或另一作者已确认不足以判定归属；证据不足／真正冲突交核验 | [scholar-claim.md](modules/scholar-claim.md) |
| BR-511 | 统计中的"成果数量"均指**去重后的成果实体数量**，不是来源记录条数或版本记录条数 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-512 | 学者维度与机构维度的计数合计可分别大于平台成果实体总量；同一成果关联多个领域时在各领域分别计入。该现象必须在口径声明中说明，不得隐藏，也不得为让合计相等而人为去重 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-513 | 机构归属取**成果发生时的机构归属**；缺失时标为"机构未知"，不得使用学者当前任职机构代替；"机构未知"不参与机构名次 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-514 | 任何统计、排名、热点或网络结果必须与结果同屏披露数据范围、时间范围、计数口径、统计条件与生成时间；模拟数据结果另须标识数据集来源 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-515 | 真实数据与模拟数据分开标识、支持过滤；使用模拟数据的结果不得用于描述真实世界的机构排名、热点或合作关系结论 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-516 | 同一成果实体的多条来源记录只计一次，且只增加一次关系强度；重试或重复导入不得造成重复计数 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-517 | 任意两名学者之间只有一条合作关系边；作者顺序不同、成果不同均不产生新边 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-518 | FR12 每条合作关系须可追溯到至少一项合格共同成果；IN01 路径每一跳须有合格共同论文依据，非论文成果不得替代。FR12 共同成果数与 IN01 路径论文证据数分别声明 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-519 | 分离成果实体统计资格与学者—成果关系资格（§7.2）：有效公开成果不因部分作者归属待核验而整体退出成果统计；网络仅采用两端均合格的共同成果关联，待核验／争议／已排除关联不成边、不增加强度。实体和关联排除数量分别披露；IN01 路径另限论文 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-520 | 计数低于约定阈值 T 的统计条目，或参与成果数量低于 T 的时间点，须标注"样本不足"并仍展示实际计数；不得隐藏、补足或用默认值替代（T 见 Q-1） | [analytics-graph.md](modules/analytics-graph.md) |
| BR-521 | 关键词与主题的归一必须基于可查询的规则；规则变更必须触发相关热点结果重算；无法判定时不合并并标注 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-522 | 热点指标、关系强度、机构排名均不得被表述或暗示为研究质量、影响力、学术水平或机构优劣的结论；相关页面须固定展示对应声明 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-523 | 纳入统计范围的数据或口径变更后，统计、排名、热点与合作网络须在 NF05 约定时限内与检索、门户反映一致结果；未完成或失败时须给出可观察提示并展示上一版可用结果 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-524 | IN01 的筛选与最短路径计算只能在已声明的数据图范围内进行；范围外关系视为不存在；不得依据机构、领域、姓名等旁证推断关系 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-525 | 路径长度以合作边数定义；存在多条等长最短路径时须全部列出，或按约定规则展示并说明规则与总条数 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-526 | 统计、排名、热点、合作网络与路径查询为访客可用的公开能力；访客不得看到待核验／争议关系明细、审核材料、未公开对象与个人敏感字段 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-527 | 预印本与正式发表记录被确认同一时按一个成果实体计数；未确认时保留候选关系并分别计数，口径说明给出待确认候选数量 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-528 | 统计视图与网络视图须声明单次展示规模上限；超过时提示当前规模并引导收窄；展示子集须标注"部分结果（已声明子集）"，不得描述为完整结果（上限见 Q-6） | [analytics-graph.md](modules/analytics-graph.md) |
| BR-529 | 引用量按来源分别展示，不求和生成"总引用量"；本分册任何统计视图若展示引用相关数值须遵守（引用 V1.9 第 5.3 节） | [analytics-graph.md](modules/analytics-graph.md) |
| BR-530 | 学者档案被确认合并后，统计、排名、热点与合作网络须使用合并后的学者标识，不得同时保留重复节点，且不得出现自环 | [analytics-graph.md](modules/analytics-graph.md) |
| BR-601 | 成果去重保留全部出处，不把来源条数当成果数 | [admin-audit.md](modules/admin-audit.md) |
| BR-602 | 无有效稳定标识只能按多证据候选判定，不得仅凭标题自动合并 | [admin-audit.md](modules/admin-audit.md) |
| BR-603 | 姓名相同不足以自动合并或确认学者归属 | [admin-audit.md](modules/admin-audit.md) |
| BR-604 | 门户授权、认领申请、参与关系及成果变更状态分别表达 | [admin-audit.md](modules/admin-audit.md) |
| BR-605 | 自动更新不得静默覆盖人工确认或审核结果，冲突保留公开版本和来源差异 | [admin-audit.md](modules/admin-audit.md) |
| BR-606 | 来源停用或临时不可用不删除已收录成果，历史数据仍可浏览并标更新时间 | [admin-audit.md](modules/admin-audit.md) |
| BR-608 | 业务审核通过不构成同行评审、学术质量或研究真实性认证 | [admin-audit.md](modules/admin-audit.md) |
| BR-609 | 保留本地分类、来源原分类及映射，无法可靠映射标为待分类 | [admin-audit.md](modules/admin-audit.md) |
| BR-611 | 冲突认领不自动转移管理权，未解决冲突不得普通批准 | [admin-audit.md](modules/admin-audit.md) |
| BR-612 | 公开视图仅展示获准公开的元数据，待核验信息不得冒充已确认；后台受控证据按权限访问 | [admin-audit.md](modules/admin-audit.md) |
| BR-613 | 真实与模拟数据显式标识，不用模拟记录冒充真实科研证据 | [admin-audit.md](modules/admin-audit.md) |
| BR-614 | 数据最小化，认领审核材料受控访问，清理与访问细则待评审 | [admin-audit.md](modules/admin-audit.md) |
| BR-615 | 治理结论生效后展示一致并保留变更记录；60秒仅是NF05约定范围内待评审目标，不是账号权限撤销宽限 | [admin-audit.md](modules/admin-audit.md) |
| BR-616 | O02仅在选择实施且明确同意后使用必要行为，提供本人偏好查看与清除 | [admin-audit.md](modules/admin-audit.md) |

## 3. 图注册

| 图号 | 唯一图源 | 生成图 | 阶段与状态 |
| --- | --- | --- | --- |
| DGM-ACTIVITY-02 | [DGM-ACTIVITY-02-metrics-refresh.puml](../../docs/uml/analytics/DGM-ACTIVITY-02-metrics-refresh.puml) | [SVG](../../docs/uml/analytics/generated/DGM-ACTIVITY-02-metrics-refresh.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-03 | [DGM-ACTIVITY-03-collab-path.puml](../../docs/uml/analytics/DGM-ACTIVITY-03-collab-path.puml) | [SVG](../../docs/uml/analytics/generated/DGM-ACTIVITY-03-collab-path.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-04 | [DGM-ACTIVITY-04-institution-ranking.puml](../../docs/uml/analytics/DGM-ACTIVITY-04-institution-ranking.puml) | [SVG](../../docs/uml/analytics/generated/DGM-ACTIVITY-04-institution-ranking.svg) | 需求建模（业务待决以正文为准） |
| DGM-STATE-03 | [DGM-STATE-03-metrics-visibility.puml](../../docs/uml/analytics/DGM-STATE-03-metrics-visibility.puml) | [SVG](../../docs/uml/analytics/generated/DGM-STATE-03-metrics-visibility.svg) | 需求建模（业务待决以正文为准） |
| DGM-USECASE-02 | [DGM-USECASE-02-analytics.puml](../../docs/uml/analytics/DGM-USECASE-02-analytics.puml) | [SVG](../../docs/uml/analytics/generated/DGM-USECASE-02-analytics.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-01 | [DGM-ACTIVITY-01-claim-review.puml](../../docs/uml/claim/DGM-ACTIVITY-01-claim-review.puml) | [SVG](../../docs/uml/claim/generated/DGM-ACTIVITY-01-claim-review.svg) | 需求建模（业务待决以正文为准） |
| DGM-STATE-02 | [DGM-STATE-02-claim-request.puml](../../docs/uml/claim/DGM-STATE-02-claim-request.puml) | [SVG](../../docs/uml/claim/generated/DGM-STATE-02-claim-request.svg) | 需求建模（业务待决以正文为准） |
| DGM-USECASE-03 | [collection-local-usecase.puml](../../docs/uml/collection/collection-local-usecase.puml) | [SVG](../../docs/uml/collection/generated/collection-local-usecase.svg) | 需求建模（业务待决以正文为准） |
| DGM-STATE-01 | [collection-task-state.puml](../../docs/uml/collection/collection-task-state.puml) | [SVG](../../docs/uml/collection/generated/collection-task-state.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-05 | [collection-update-activity.puml](../../docs/uml/collection/collection-update-activity.puml) | [SVG](../../docs/uml/collection/generated/collection-update-activity.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-08 | [dedup-candidate-activity.puml](../../docs/uml/governance/dedup-candidate-activity.puml) | [SVG](../../docs/uml/governance/generated/dedup-candidate-activity.svg) | 需求建模（业务待决以正文为准） |
| DGM-STATE-04 | [governance-exception-state.puml](../../docs/uml/governance/governance-exception-state.puml) | [SVG](../../docs/uml/governance/generated/governance-exception-state.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-06 | [governance-overview-activity.puml](../../docs/uml/governance/governance-overview-activity.puml) | [SVG](../../docs/uml/governance/generated/governance-overview-activity.svg) | 需求建模（业务待决以正文为准） |
| DGM-ACTIVITY-07 | [identity-disambiguation-activity.puml](../../docs/uml/governance/identity-disambiguation-activity.puml) | [SVG](../../docs/uml/governance/generated/identity-disambiguation-activity.svg) | 需求建模（业务待决以正文为准） |
| DGM-SEQ-01 | [relation-decision-sequence.puml](../../docs/uml/governance/relation-decision-sequence.puml) | [SVG](../../docs/uml/governance/generated/relation-decision-sequence.svg) | 后续分析参考 |
| DGM-STATE-05 | [scholar-achievement-relation-state.puml](../../docs/uml/governance/scholar-achievement-relation-state.puml) | [SVG](../../docs/uml/governance/generated/scholar-achievement-relation-state.svg) | 需求建模（业务待决以正文为准） |
| DGM-USECASE-01 | [overall-usecase.puml](../../docs/uml/usecase/overall-usecase.puml) | [SVG](../../docs/uml/usecase/generated/overall-usecase.svg) | 需求建模（业务待决以正文为准） |

## 4. 旧局部号兼容映射

只在本节及历史审查快照中保留旧号。现行正文、图源和追踪一律采用新号。

| 原来源或作用域 | 旧局部编号 | 现行全局编号 |
| --- | --- | --- |
| search | BR-COM-01 | BR-101 |
| search | BR-COM-02 | BR-102 |
| search | BR-COM-03 | BR-103 |
| search | BR-COM-04 | BR-104 |
| search | BR-COM-05 | BR-105 |
| search | BR-COM-06 | BR-106 |
| search | BR-COM-07 | BR-107 |
| search | BR-FR01-01 | BR-108 |
| search | BR-FR01-02 | BR-109 |
| search | BR-FR01-03 | BR-110 |
| search | BR-FR01-04 | BR-111 |
| search | BR-FR01-05 | BR-112 |
| search | BR-FR01-06 | BR-113 |
| search | BR-FR01-07 | BR-114 |
| search | BR-FR02-01 | BR-115 |
| search | BR-FR02-02 | BR-116 |
| search | BR-FR02-03 | BR-117 |
| search | BR-FR02-04 | BR-118 |
| search | BR-FR02-05 | BR-119 |
| search | BR-FR02-06 | BR-120 |
| search | BR-O01-01 | BR-121 |
| search | BR-O01-02 | BR-122 |
| search | BR-O01-03 | BR-123 |
| search | BR-O02-01 | BR-124 |
| search | BR-O02-02 | BR-125 |
| search | BR-O02-03 | BR-126 |
| search | BR-O02-04 | BR-127 |
| governance | BR-DUP-01 | BR-301 |
| governance | BR-DUP-02 | BR-302 |
| governance | BR-DUP-03 | BR-303 |
| governance | BR-DUP-04 | BR-304 |
| governance | BR-DUP-05 | BR-305 |
| governance | BR-DUP-06 | BR-306 |
| governance | BR-DUP-07 | BR-307 |
| governance | BR-DUP-08 | BR-308 |
| governance | BR-DUP-09 | BR-309 |
| governance | BR-DUP-10 | BR-310 |
| governance | BR-DUP-11 | BR-311 |
| governance | BR-DATA-01 | BR-321 |
| governance | BR-DATA-02 | BR-322 |
| governance | BR-DATA-03 | BR-323 |
| governance | BR-DATA-04 | BR-324 |
| governance | BR-DATA-05 | BR-325 |
| governance | BR-DATA-06 | BR-326 |
| governance | BR-DATA-07 | BR-327 |
| governance | BR-SCH-01 | BR-341 |
| governance | BR-SCH-02 | BR-342 |
| governance | BR-SCH-03 | BR-343 |
| governance | BR-SCH-04 | BR-344 |
| governance | BR-SCH-05 | BR-345 |
| governance | BR-SCH-06 | BR-346 |
| governance | BR-SCH-07 | BR-347 |
| governance | BR-SCH-08 | BR-348 |
| governance | BR-SCH-09 | BR-349 |
| governance | BR-SCH-10 | BR-350 |
| governance | BR-SCH-11 | BR-351 |
| governance | BR-SCH-12 | BR-352 |
| governance | BR-SCH-13 | BR-353 |
| governance | BR-SCH-14 | BR-354 |
| governance | BR-SCH-15 | BR-355 |
| governance | BR-SCH-16 | BR-356 |
| governance | BR-SCH-17 | BR-357 |
| governance | BR-SCH-18 | BR-358 |
| governance | BR-SCH-19 | BR-359 |
| governance | BR-SCH-20 | BR-360 |
| governance | BR-SCH-21 | BR-361 |
| governance | BR-PRIV-01 | BR-371 |
| governance | BR-AUDIT-01 | BR-381 |
| governance | BR-AUDIT-02 | BR-382 |
| claim | BR-01 | BR-401 |
| claim | BR-02 | BR-402 |
| claim | BR-03 | BR-403 |
| claim | BR-04 | BR-404 |
| claim | BR-05 | BR-405 |
| claim | BR-06 | BR-406 |
| claim | BR-07 | BR-407 |
| claim | BR-08 | BR-408 |
| claim | BR-09 | BR-409 |
| claim | BR-10 | BR-410 |
| claim | BR-11 | BR-411 |
| claim | BR-12 | BR-412 |
| claim | BR-13 | BR-413 |
| claim | BR-14 | BR-414 |
| claim | BR-15 | BR-415 |
| claim | BR-16 | BR-416 |
| claim | BR-17 | BR-417 |
| claim | BR-18 | BR-418 |
| claim | BR-19 | BR-419 |
| claim | BR-20 | BR-420 |
| claim | BR-21 | BR-421 |
| claim | BR-22 | BR-422 |
| claim | BR-23 | BR-423 |
| analytics | BR-11 | BR-511 |
| analytics | BR-12 | BR-512 |
| analytics | BR-13 | BR-513 |
| analytics | BR-14 | BR-514 |
| analytics | BR-15 | BR-515 |
| analytics | BR-16 | BR-516 |
| analytics | BR-17 | BR-517 |
| analytics | BR-18 | BR-518 |
| analytics | BR-19 | BR-519 |
| analytics | BR-20 | BR-520 |
| analytics | BR-21 | BR-521 |
| analytics | BR-22 | BR-522 |
| analytics | BR-23 | BR-523 |
| analytics | BR-24 | BR-524 |
| analytics | BR-25 | BR-525 |
| analytics | BR-26 | BR-526 |
| analytics | BR-27 | BR-527 |
| analytics | BR-28 | BR-528 |
| analytics | BR-29 | BR-529 |
| analytics | BR-30 | BR-530 |
| admin | BR-01 | BR-601 |
| admin | BR-02 | BR-602 |
| admin | BR-03 | BR-603 |
| admin | BR-04 | BR-604 |
| admin | BR-05 | BR-605 |
| admin | BR-06 | BR-606 |
| admin | BR-08 | BR-608 |
| admin | BR-09 | BR-609 |
| admin | BR-11 | BR-611 |
| admin | BR-12 | BR-612 |
| admin | BR-13 | BR-613 |
| admin | BR-14 | BR-614 |
| admin | BR-15 | BR-615 |
| admin | BR-16 | BR-616 |
| uc_source | UC-08 | UC-18 |
| uc_source | UC-10 | UC-19 |
| uc_governance | UC-05 | UC-06 |
| uc_governance | UC-06 | UC-08 |
| uc_governance | UC-08 | UC-18 |
| uc_admin | UC-12 | UC-17 |
| uc_admin | UC-09 | UC-18 |
| dgm_source | DGM-USECASE-02 | DGM-USECASE-03 |
| dgm_source | DGM-ACTIVITY-02 | DGM-ACTIVITY-05 |
| dgm_governance | DGM-ACTIVITY-02 | DGM-ACTIVITY-06 |
| dgm_governance | DGM-ACTIVITY-03 | DGM-ACTIVITY-07 |
| dgm_governance | DGM-ACTIVITY-04 | DGM-ACTIVITY-08 |
| dgm_governance | DGM-STATE-03 | DGM-STATE-05 |

未变更：门户UC-03～08、治理UC-09/10、统计UC-11～15、来源UC-16；BR-201～212；总体用例DGM-USECASE-01、认领活动DGM-ACTIVITY-01、采集DGM-STATE-01、认领申请DGM-STATE-02、统计五图。新增UC-01/02/17/20/21只为既有需求补全追踪，不新增业务能力。
