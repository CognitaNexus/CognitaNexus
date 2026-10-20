# S02 BCE 分析类模型与职责

**版本：** V1.0（2026-10-20）  
**图源：** [`DGM-CLASS-01-analysis.puml`](../../uml/analysis/DGM-CLASS-01-analysis.puml)  
**导出：** [SVG](../../uml/analysis/generated/DGM-CLASS-01-analysis.svg) · [PNG](../../uml/analysis/generated/DGM-CLASS-01-analysis.png)  
**方法：** 教材 §5.3.1、§5.4.3–§5.4.4、§5.5；Boundary/Control/Entity 仅表达分析职责，不是实现分层或数据库设计。

## 1 模型目的

该模型把 S01 的关键抽象和 S02 关键用例的交互职责放在同一张分析类图中，用于检查：

- 每个外部交互是否有 Boundary 承接；
- 每个跨步骤守卫/生效协调是否有 Control 负责；
- 每个持久业务事实是否由独立 Entity 表达；
- User、UserProfile、Scholar、Authorization、Authorship 是否被错误合并；
- FR12 四类基础成果与 IN01 论文子图是否在职责上分开。

## 2 Boundary 类

| Boundary | 对应 UC | 职责 | 不负责 |
|---|---|---|---|
| PublicPortalUI | UC-01/02/03/11/12/13/14/15/16 | 展示公开检索、详情、主页、统计、网络与错误/空态 | 决定权限、合并身份、计算去重 |
| AccountUI | UC-04 | 注册、登录、退出、账号状态提示 | 创建 Scholar 或 Authorship |
| ProfileUI | UC-22 | 编辑本人普通 UserProfile 字段 | 写 Scholar 学术事实 |
| ScholarPortalUI | UC-03/07 | 展示/维护授权 Scholar 档案 | 编辑普通主页 |
| ClaimUI | UC-05/06 | 提交/查询/审核认领 | 直接授予对象权（由 Control 协调） |
| SearchUI | UC-01/02 | 查询条件、结果、失败/空态 | 伪造查询成功 |
| AnalyticsUI | UC-11～15 | 统计、排名、热点、基础网络/路径展示 | 将专利边画成论文路径 |
| AdminConsole | UC-17/18/19 | 管理待办、来源、任务、审计查询 | 绕过具体权限 |
| SourceAdapter | UC-18/19 | 与获准来源交换记录和失败信息 | 自行决定公开/归属 |

## 3 Control 类

| Control | 协调职责 | 关键守卫 | 证据 |
|---|---|---|---|
| RegistrationCoordinator | 注册校验、User+UserProfile 同事务创建、稳定URL、失败回滚/幂等 | 重试不产生第二主页；不创建Scholar | FR04-03/04；UC-04 |
| ProfileCoordinator | 校验会话/所有权，区普通资料与学术事实 | 他人/停用拒绝；普通编辑不写核验状态 | FR06-03/04/05；UC-22 |
| ClaimReviewCoordinator | 证据校验、待审/补充/争议、批准授予对象权 | 授权失败不虚报；原授权不自动转移；不建第二主页 | FR05-01～04；UC-05/06 |
| SearchCoordinator | 校验条件、公开过滤、稳定排序/分页、失败与空分离 | 待审/无权不进入；固定快照 | FR01/02；UC-01/02 |
| CollectionCoordinator | 任务生命周期、检查点、读取、处置汇总 | 队列交接失败不算成功；重试不增重复实体 | FR08/10；UC-18/19 |
| GovernanceCoordinator | 清洗/去重/身份/归属候选处理 | 无稳定键转待核验；不改写原证据 | FR09/15；UC-09/10 |
| AnalyticsNetworkCoordinator | 四类合格基础图、论文子图、筛选/BFS/等长规则 | 同机构/同领域不建边；非论文边不进IN01；超限拒绝 | FR12/IN01；UC-14/15 |
| AuditCoordinator | 受保护写入、审核决定、前后值/理由/时间 | 普通用户不可看私有证明 | FR14；UC-17 |

## 4 Entity 类

| Entity | 独立存在理由 | 关键关系 | 不能混淆 |
|---|---|---|---|
| User | 账号/会话/对象授权主体 | 1:1 UserProfile；提交Claim；被授予Auth | 不等于Scholar；不因注册生成Authorship |
| UserProfile | 所有注册用户唯一公开普通主页 | 属于一个User；不强制对应Scholar | 不是Scholar档案；兴趣/简介不作学术证据 |
| Scholar | 平台采集/核验的学者身份，可无账号 | 被Claim目标；被Auth授权；通过Authorship参与成果 | 同名不合并；认领前仍可公开 |
| ClaimApplication | 申请/补证/争议/审核生命周期 | 关联User与Scholar；批准产生Auth | 不是Authorship状态 |
| ScholarAuthorization | 目标对象级管理权 | User×Scholar；批准后生效 | 不等于本人确认；不自动转移 |
| SourceRecord | 原始来源、许可、逻辑出处、到达事件 | 产生/支持WorkEntity | 不等于成果实体；重复到达不增成果 |
| WorkEntity | 去重后的四类成果规范实体 | 多SourceRecord；WorkVersion；Authorship | 来源数/版本数不等于成果数 |
| WorkVersion | 有明示证据的预印本/正式版等 | 属于WorkEntity | 无证据不合并 |
| Authorship | 学者—成果参与关系，携带角色/顺序/证据/状态 | Scholar×WorkEntity | 管理员审核不等于本人确认；解除不删成果 |
| CollectionTask | 可停止/重试/检查点的任务 | 读取SourceRecord，产生处置 | 不等于调度器/线程 |
| ReviewEvidence | 审核/修正/授权的可追溯决定 | 关联Claim/Authorship/更正 | 不作为公开实体信息 |

## 5 关键一致性检查

1. **User–UserProfile 1:1**：注册协调器同事务创建；UC-22只改本人普通字段；Claim批准不另建主页。
2. **UserProfile≠Scholar**：同名时分别展示；普通资料不进入 Scholar 身份/统计/合作边。
3. **Authorship≠成果**：解除某学者关系保留WorkEntity和其他合法Authorship；单次本人确认不扩散。
4. **FR12四类 / IN01论文**：基础图协调器接受四类合法角色；路径协调只取论文子图，避免把AnalyticsNetworkCoordinator误做成单一论文图。
5. **来源/成果分离**：SourceRecord保留原值；WorkEntity去重；重复来源/版本不重复计强度。
6. **审计与授权分离**：ClaimReviewCoordinator授予对象权，但不把“管理员批准”写成本人确认；ReviewEvidence独立留痕。

## 6 边界声明

- 图中未出现 Controller/Service/Repository、数据库表、消息队列、搜索引擎、缓存或部署节点。
- 属性仅为业务信息提示，不代表字段类型、主键或API。
- 图中关系为分析关联；除User–UserProfile的1:1外，其余多重性仍需后续实现设计确认。
- 该模型不替代S02用例规约和交互序列；任何职责争议以UC/AC证据修正模型，而不是以模型反向改需求。
