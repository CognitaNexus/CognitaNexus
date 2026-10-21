# UC-03 分析与现有全局分析类差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml) 与 [`01-BCE分析类模型.md`](../01-BCE分析类模型.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图，也不修改共享的 `analysis-class-registry.md`。

## 1. 已存在且一致

| UC-03 类/概念 | 全局图对应 | 一致点 | 证据 |
|---|---|---|---|
| `UserProfile` | `UserProfile`（全局图第 53–57 行） | 都有稳定公开地址、普通公开资料、零成果空态；与 `Scholar` 分离 | FR03；`AC-IMPL-FR03-03/04`；SRS §12 |
| `Scholar` | `Scholar`（第 58–61 行） | 都是独立学者身份，可无账号；不因同名合并 | FR03；BR-IMP-03；SRS §12 |
| `Scholar — Authorship — Work` | 全局 `Scholar -- Authorship -- Work`、`Scholar -- Authorship`、`Authorship -- Work` | 参与关系经 `Authorship` 表达，成果不由学者直连 | SRS §12；FR15；§7.2 |
| `Authorship` 关系状态 | 全局 `+角色/顺序/证据()`、`+独立关系状态()` 与 note“管理员审核不等于本人确认” | 状态独立、不得冒充本人确认 | SPEC ID-06；`AC-IMPL-FR03-02` |
| 公开视图无 `User—Scholar` 合并 | 全局 `User 1—1 UserProfile`，无 `UserProfile—Scholar` 边 | 不强制合并 | FR03-04；SRS §12 |
| 公开读取边界 | 全局 `PublicUI`（`DGM-CLASS-01` 第 16 行）；`01-BCE分析类模型.md` 第 22 行 `PublicPortalUI` 写明 UC-01/02/03/11/12/13/14/15/16 | 公开读取有独立边界 | FR03；教材 §5.4.2 |
| `ScholarPortalUI` 承接档案展示 | 全局 `ScholarPortalUI`（`01` 第 25 行：UC-03/07） | UC-03 需要档案展示边界 | FR03 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `PublicProfileUI` | `PublicUI`、`ScholarPortalUI` | 全局把 UC-03 同时挂在两个边界上；本 UC 只有一个只读边界 | 二选一：把 `PublicUI` 明确为公开读取边界（含主页/档案）并把 `ScholarPortalUI` 限定为 UC-07 授权维护；或按对象拆成 `PublicProfileUI` + `ScholarArchiveUI` 两个只读边界 |
| `PublicProfileCoordinator` | 全局无同名类；`PublicUI ..> SearchCtl` | 全局没有公开主页/档案读取的专用协调者，`SearchCtl` 负责检索条件/排序/分页 | 新增或明确公开读取协调者；不要把主页/档案读取并入 `SearchCtl` |
| `WorkEntity` | `Work` | 名称别名（`analysis-class-registry.md` 已登记） | 统一正式名；沿用 D-11 统一处理 |
| `Authorship`、`Scholar`、`UserProfile` | 同名 | 名称一致 | 保留 |

## 3. 职责冲突/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 对象类型解析与同名隔离 | `AC-IMPL-FR03-04`；BR-IMP-03；SPEC ID-01/SR-12 | 全局 `PublicUI ..> SearchCtl` 未表达“按稳定标识解析、禁止同名合并” | 若主页/档案读取沿用 `SearchCtl`，会把“按姓名检索/召回”与“按标识打开一个对象”混淆 | 在公开读取协调职责中写明“按稳定标识解析、不按姓名合并”，并与 `SearchCtl` 分离 |
| 零成果空态非 404 | `AC-IMPL-FR03-03` | 全局 `UserProfile +零成果空态()` 有属性级提示，未见“不得返回 404/不得伪装失败”的守卫 | 空态与不可访问容易被实现成同一个 404 | 在协调者/边界职责中写明三态分离 |
| 未认领提示 | `AC-IMPL-FR03-01` | 全局 `Scholar +已核验身份()`、`+公开学者档案()`，无认领状态/未认领提示 | UC-03 无法从全局类定义看出“未认领仍可公开并提示” | 建议在 `Scholar` 职责中补“认领状态与未认领提示”，并区分认领状态与身份核验状态 |
| 公开字段授权范围 | FR03“公开字段只包含用户允许展示的信息”；FR04-04；BR-IMP-11 | 全局 `UserProfile +普通公开资料()` 未表达“允许展示”的限定与最小化 | 容易把全部普通资料当默认公开 | 建议在 `UserProfile` 职责/属性中补“允许公开的字段范围”，注册邮箱/联系方式默认不公开 |
| 关系状态展示过滤 | `AC-IMPL-FR03-02`；FR15 | 全局 `Authorship` 有独立状态，但没有公开读取侧的过滤职责；`GraphCtl/GovernCtl` 负责派生与治理 | 展示侧若无守卫，待核验关系可能被渲染成“已确认合作” | 在公开读取协调职责中加入“关系状态不冒充本人确认”的统一过滤 |
| 公开最小化守卫 | `AC-IMPL-NF06-01`；BR-IMP-11 | 全局 `PublicUI` 未表达凭据/审核材料/私有证明屏蔽 | 公开响应可能出现私有证明 | 建议在公开读取边界/协调者加最小化职责（与 `AuditCtl` 的证据职责分开） |
| 边界读写分离 | FR04-04；BR-IMP-05 | `ScholarPortalUI` 同时承担“展示/维护授权 Scholar 档案”，读与写混在一个边界 | UC-03 只读、UC-07 受保护写，权限要求不同 | R5 拆分为公开只读边界 + 授权维护边界 |
| `ProfileCtl ..> Scholar` | 本 UC 只读且不改 Scholar | 全局 `ProfileCtl`（UC-22 编辑）关联到 `Scholar` | 与 UC-03 无直接冲突，但说明“普通资料编辑”与“学者档案”边界需要明确 | R5 保留 `ProfileCtl` 仅处理普通资料；学者档案写入归 UC-07 |

## 4. 关系差异

| 关系 | 全局现状 | 本 UC 结论 | R5 建议 |
|---|---|---|---|
| `Scholar — Authorship` | 存在（`Scholar -- Authorship : 身份参与`） | 一致 | 保留 |
| `Authorship — Work` | 存在（`Authorship -- Work : 参与成果`） | 一致 | 保留 |
| `Scholar — Work`（直连） | 不存在 | 本 UC 也不建立，禁止直连 | 保留“必须经 Authorship”的说明 |
| `UserProfile — Scholar` | 不存在 | 本 UC 明确禁止合并/强制关联 | 保留，并在 R5 说明“认领后的关联由 FR05 授权记录表达，不画成主页—档案等价” |
| `User 1 — 1 UserProfile` | 存在（第 116 行） | 一致但不参与本 UC 序列（Q-09） | 保留；不要为公开读取拉入 `User`，除非先冻结停用账号的主页可读性规则 |
| `PublicUI ..> SearchCtl` | 存在 | 本 UC 的读取不由 `SearchCtl` 承担 | R5 评估：`PublicUI` 分别依赖“检索协调者”与“公开读取协调者” |
| `PublicUI — 其他公开视图` | `PublicUI`/`PublicPortalUI` 同时覆盖检索/详情/主页/统计/网络（`01` 第 22 行） | UC-02 已提出 `WorkDetailCoordinator`；本 UC 提出 `PublicProfileCoordinator` | R5 统一公开读取协调者边界，避免一个 `SearchCtl` 承担三种读取 |

## 5. 当前不能确定的关系

| 关系 | 为什么不能在本 UC 确定 | 留待 |
|---|---|---|
| UserProfile — WorkEntity / Authorship | 主页成果区的数据来源口径未冻结（Q-03）；SRS §12 只禁止由普通资料推导学术身份/合作边 | 需求确认后由 UC-22/FR06 或后续统一分析类处理 |
| UserProfile — Scholar（认领后） | 认领授权的表达形式属 FR05；本 UC 不读取授权记录 | UC-05/06 与 R5 |
| Scholar 身份核验状态枚举 | SPEC 的身份/关系状态枚举到门户标签的映射需联合评审 | Q-06；R5 |
| 停用账号对 UserProfile 公开性的影响 | `AC-IMPL-FR04-02` 只保证不删除学者公开档案 | Q-09；FR04 后续确认 |
| 待核验关系是否公开显示 | `AC-IMPL-FR03-02` 只禁止冒充；BR-IMP-01 只排除待审/争议/敏感证据 | Q-04；评审确认 |

## 6. 跨 UC 类登记建议（不改共享登记表）

`automation/analysis-class-registry.md` 由父 Agent/后续统一阶段维护，本 UC 只提交以下登记建议：

| 正式名称 | BCE | 首次发现 UC | 后续参与 UC | 主要职责 | 必要业务属性 | 已有关系 | 未定多重性 | 别名/冲突 | R5 建议 |
|---|---|---|---|---|---|---|---|---|---|
| PublicProfileUI | Boundary | UC-03 | — | 承接公开主页/档案查看；展示空态、未认领提示、不可访问、失败 | 无持久业务属性 | → PublicProfileCoordinator | 不定义持久多重性 | 全局 `PublicUI`、`ScholarPortalUI` | 统一公开只读边界 |
| PublicProfileCoordinator | Control | UC-03 | — | 稳定标识解析、公开过滤、关系状态不冒充、三态分离、单一对象视图 | 查看请求上下文（单次） | → UserProfile/Scholar/Authorship/WorkEntity | 不定义持久多重性 | 全局无；不要并入 `SearchCtl` | 新增或明确职责 |

既有登记行的“后续参与 UC”建议补充：`UserProfile`、`Scholar`、`Authorship`、`WorkEntity` 均新增 UC-03；`Scholar` 的“不得与 UserProfile 合并”已有备注，保持不变。

## 7. 差异结论

UC-03 分析支持以下全局模型调整建议：

1. 保留 `UserProfile`、`Scholar`、`Authorship`、`WorkEntity` 及其 `Scholar—Authorship—Work` 关系；
2. 统一公开读取边界（`PublicUI`/`ScholarPortalUI`/`PublicProfileUI`），并把只读展示与受保护维护分开；
3. 公开主页/档案读取应有独立协调职责，不应路由到 `SearchCtl`；
4. 在公开读取职责中补：按稳定标识解析、同名不合并、零成果空态非 404、未认领提示、关系状态不冒充、公开最小化；
5. `UserProfile—Scholar` 不得画成合并/等价关系；`Scholar—Work` 不得直连；
6. 不在本 UC 引入 `User`、`Claim`、`Auth`、`Evidence`、`SourceRecord`。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md` 与共享登记表。
