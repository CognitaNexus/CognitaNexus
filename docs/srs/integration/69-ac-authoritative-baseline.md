# 69 条 AC 权威验收基线（V2.2）

**状态：S01 FROZEN — 69 AC BASELINE**  
**冻结输入：**《03组-可御风雪可燃冰》需求建模成果 `01-SRS-03组-可御风雪可燃冰.docx` V2.1；69 条 `AC-IMPL` 原文。  
**机器账本：** [`69-ac-baseline.json`](69-ac-baseline.json)  
**追踪：** [`69-ac-traceability.md`](69-ac-traceability.md)  
**正式 SRS：** [`CognitaNexus-Shared-SRS-V2.2-69AC.md`](../CognitaNexus-Shared-SRS-V2.2-69AC.md)

> 本清单是唯一 69 条验收基线。46 条与 59 条版本只作历史差异；不新增第 70 条，不删除任何原条目，不以同号自动合并不同语义。执行状态列不参与 AC 计数，测试未执行一律 `NOT RUN`。

## 数量核对

| 分组 | 数量 |
|---|---:|
| FR01–FR16 | 54 |
| IN01 | 4 |
| NF01–NF08 | 11 |
| **合计** | **69** |

## 权威 AC 清单

| # | 最终 AC ID | 原文验收条件 | 父项 | 业务规则 | 主用例 | 来源 | 涉及 A1c/规模 | 执行状态 |
|---:|---|---|---|---|---|---|---|---|
| 1 | `AC-IMPL-FR01-01` | Given 同词匹配一成果一学者，When 仅在成果范围查询，Then 只返回公开成果，不返回学者或待审成果。 | FR01 | BR-IMP-01/07 | UC-01 | SRS V2.1 P214 | 否 | 计划验收，未执行 |
| 2 | `AC-IMPL-FR01-02` | Given 同时选论文、2025年、机构甲，When 返回结果及下一页，Then 所有行符合全部条件、页间不重不漏、返回后筛选保留。 | FR01 | BR-IMP-01/07 | UC-01 | SRS V2.1 P215 | 否 | 计划验收，未执行 |
| 3 | `AC-IMPL-FR01-03` | Given 无匹配或依赖超时，When 查询，Then 分别返回无结果与执行失败，不混淆。 | FR01 | BR-IMP-01/07 | UC-01 | SRS V2.1 P216 | 否 | 计划验收，未执行 |
| 4 | `AC-IMPL-FR02-01` | Given 同成果有两条许可来源，When 打开详情，Then 一条成果实体展示两条来源，字段缺失标未知。 | FR02 | BR-IMP-01/02 | UC-02、UC-16 | SRS V2.1 P221 | 否 | 计划验收，未执行 |
| 5 | `AC-IMPL-FR02-02` | Given 原站外链失效，When 打开已收录详情，Then 元数据仍可读，外链明确失败而非删除成果。 | FR02 | BR-IMP-01/02 | UC-02、UC-16 | SRS V2.1 P222 | 否 | 计划验收，未执行 |
| 6 | `AC-IMPL-FR03-01` | Given 学者未认领且有已生效成果，When 访客打开门户，Then 可以浏览并看到未认领提示。 | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | SRS V2.1 P227 | 是 | 计划验收，未执行 |
| 7 | `AC-IMPL-FR03-02` | Given 一名学者关联待核验成果，When 查看公开关系，Then 待核验不得显示成已确认合作或本人确认。 | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | SRS V2.1 P228 | 是 | 计划验收，未执行 |
| 8 | `AC-IMPL-FR03-03` | Given 用户甲刚注册且无论文/专利/项目/获奖，When 访客凭其稳定主页地址访问，Then 能正常打开个人主页并看到“暂无公开成果”，不因零成果返回404。 | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | SRS V2.1 P229 | 是 | 计划验收，未执行 |
| 9 | `AC-IMPL-FR03-04` | Given 普通用户资料和另一位同名Scholar档案，When 访客分别打开链接，Then 页面对象与核验状态区分显示，不自动合并或冒充已认证学者。 | FR03 | BR-IMP-01/03/11 | UC-03、UC-22 | SRS V2.1 P230 | 是 | 计划验收，未执行 |
| 10 | `AC-IMPL-FR04-01` | Given 登录用户有门户甲授权而无门户乙授权，When 修改乙，Then 拒绝且不写入；前端隐藏入口不能取代拒绝。 | FR04 | BR-IMP-05/10/11 | UC-04 | SRS V2.1 P235 | 是 | 计划验收，未执行 |
| 11 | `AC-IMPL-FR04-02` | Given 账号被停用或已退出，When 发起受保护请求，Then 拒绝而不删除该用户关联的学者公开档案。 | FR04 | BR-IMP-05/10/11 | UC-04 | SRS V2.1 P236 | 是 | 计划验收，未执行 |
| 12 | `AC-IMPL-FR04-03` | Given 新用户注册成功且暂无学术身份，When 查询账号和个人主页，Then 恰好存在一个与该User绑定的UserProfile及稳定可分享的公开URL；重试不产生第二个主页。 | FR04 | BR-IMP-05/10/11 | UC-04 | SRS V2.1 P237 | 是 | 计划验收，未执行 |
| 13 | `AC-IMPL-FR04-04` | Given 未登录访问者查看个人主页，When 读取公开资料或尝试受保护写操作，Then 公开读取成功、修改被拒绝；凭据和审核材料不出现在公开响应。 | FR04 | BR-IMP-05/10/11 | UC-04 | SRS V2.1 P238 | 是 | 计划验收，未执行 |
| 14 | `AC-IMPL-FR05-01` | Given 申请缺必要证据，When 提交，Then 不进入审核或标待补充并说明缺失。 | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | SRS V2.1 P243 | 是 | 计划验收，未执行 |
| 15 | `AC-IMPL-FR05-02` | Given 管理员通过无冲突申请，When 事务生效，Then 新授权仅对目标门户有效，申请人可查询决定。 | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | SRS V2.1 P244 | 是 | 计划验收，未执行 |
| 16 | `AC-IMPL-FR05-03` | Given 门户已有有效授权且他人争议申请，When 提交与处理，Then 原授权不转移，争议进入受控待办。 | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | SRS V2.1 P245 | 是 | 计划验收，未执行 |
| 17 | `AC-IMPL-FR05-04` | Given 用户已拥有注册自动创建的个人主页，When 其成功认领一个已有Scholar档案，Then 仅新增经过批准的学者档案关联/管理授权，不创建第二个个人主页且不自动确认全部成果归属。 | FR05 | BR-IMP-03/05/10 | UC-05、UC-06 | SRS V2.1 P246 | 是 | 计划验收，未执行 |
| 18 | `AC-IMPL-FR06-01` | Given 门户管理者提交重要机构变更，When 尚未审批，Then 公开仍为旧机构，申请人可查待审状态。 | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | SRS V2.1 P251 | 是 | 计划验收，未执行 |
| 19 | `AC-IMPL-FR06-02` | Given 未授权用户试图修改，When 直接调用接口，Then 无变更并有越权结果。 | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | SRS V2.1 P252 | 是 | 计划验收，未执行 |
| 20 | `AC-IMPL-FR06-03` | Given 普通注册用户尚未认领Scholar档案，When 修改本人昵称、头像和简介并保存，Then 无需学术审核即可按公共资料策略公开生效。 | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | SRS V2.1 P253 | 是 | 计划验收，未执行 |
| 21 | `AC-IMPL-FR06-04` | Given 用户乙没有用户甲个人主页的所有权，When 乙直接调用修改甲主页接口，Then 服务端拒绝，甲资料不变化且保留受控审计。 | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | SRS V2.1 P254 | 是 | 计划验收，未执行 |
| 22 | `AC-IMPL-FR06-05` | Given 用户填写非公开账号信息/审核材料，When 访客访问公开主页，Then 只展示明确允许公开的字段，不暴露私人联系方式、认证材料或凭据。 | FR06 | BR-IMP-05/10/11 | UC-07、UC-22 | SRS V2.1 P255 | 是 | 计划验收，未执行 |
| 23 | `AC-IMPL-FR07-01` | Given 甲解除与成果P的误关联，When 审核通过，Then P与其他合法参与者关系仍保留，甲关系更新。 | FR07 | BR-IMP-03/07/10 | UC-08 | SRS V2.1 P260 | 是 | 计划验收，未执行 |
| 24 | `AC-IMPL-FR07-02` | Given 甲提交共享成果字段修改，When 审核未通过，Then 公开旧值不变且处理过程可查。 | FR07 | BR-IMP-03/07/10 | UC-08 | SRS V2.1 P261 | 是 | 计划验收，未执行 |
| 25 | `AC-IMPL-FR07-03` | Given 用户有公开个人主页但无任何已确认成果，When 查看个人主页或成果分区，Then 正常显示空状态，不凭空生成WorkEntity或Authorship。 | FR07 | BR-IMP-03/07/10 | UC-08 | SRS V2.1 P262 | 是 | 计划验收，未执行 |
| 26 | `AC-IMPL-FR08-01` | Given 来源A获准且记录中有异常，When 处理到批次末尾，Then 任务可成功并单列待核验数量，异常不得直接成为已公开成果。 | FR08 | BR-IMP-04 | UC-18 | SRS V2.1 P267 | 否 | 计划验收，未执行 |
| 27 | `AC-IMPL-FR08-02` | Given 任务第10项失败，When 重试，Then 历史成功记录保留、不会新增重复实体，能定位失败点。 | FR08 | BR-IMP-04 | UC-18 | SRS V2.1 P268 | 否 | 计划验收，未执行 |
| 28 | `AC-IMPL-FR09-01` | Given 同DOI的两个来源论文，When 导入，Then 形成一个去重成果并保留两个出处。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P273 | 否 | 计划验收，未执行 |
| 29 | `AC-IMPL-FR09-02` | Given 两论文仅标题接近而参与者/年份冲突，When 比对，Then 进入候选/待核验而非自动合并。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P274 | 否 | 计划验收，未执行 |
| 30 | `AC-IMPL-FR09-04` | Given来源缺字段、仅年份精度及与已人工确认值冲突的更新，When清洗更新，Then缺项标未知不造值、年份不补日期、已确认值不被静默覆盖。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P275 | 否 | NOT RUN |
| 31 | `AC-IMPL-FR09-05` | Given辖区未知的专利标识及同论文的预印本/正式版，When匹配，Then辖区未知不当稳定键自动合并；有可靠版本证据时版本归并不增加成果实体数。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P276 | 否 | NOT RUN |
| 32 | `AC-IMPL-FR09-06` | Given同一来源批次重复导入，When两次执行，Then distinct实体数及distinct(source_id,source_record_key)有效出处数均不增加，允许到达事件增加。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P277 | 否 | NOT RUN |
| 33 | `AC-IMPL-FR09-03` | Given 批次重复输入，When 再次处理，Then 来源历史可追踪但去重成果数不增加。 | FR09 | BR-IMP-02/04 | UC-09 | SRS V2.1 P278 | 否 | 计划验收，未执行 |
| 34 | `AC-IMPL-FR10-01` | Given 已审核标题为B而来源更新为C，When 同步，Then 公开仍为B并出现待核验字段冲突。 | FR10 | BR-IMP-04/10 | UC-19 | SRS V2.1 P283 | 否 | 计划验收，未执行 |
| 35 | `AC-IMPL-FR10-02` | Given 来源暂时断连或删除报告，When 更新失败，Then 已收录数据仍可查询，留存失败状态与恢复入口。 | FR10 | BR-IMP-04/10 | UC-19 | SRS V2.1 P284 | 否 | 计划验收，未执行 |
| 36 | `AC-IMPL-FR11-01` | Given 同成果跨源重复且涉及机构甲乙，When 按机构统计，Then 甲乙各+1，平台去重总成果+1。 | FR11 | BR-IMP-06/07 | UC-11、UC-12 | SRS V2.1 P289 | 否 | 计划验收，未执行 |
| 37 | `AC-IMPL-FR11-02` | Given 真实P、模拟Q、混合R，When 查看默认现实排名，Then 只纳入合格P；演示模式必须标识Q/R。 | FR11 | BR-IMP-06/07 | UC-11、UC-12 | SRS V2.1 P290 | 否 | 计划验收，未执行 |
| 38 | `AC-IMPL-FR12-01` | Given甲乙共同论文P、甲丙仅共有合法合格专利Q，When查看FR12基础网络，Then项目组均有边且各有对应成果证据；When查询IN01论文路径，Then甲丙不能仅凭Q成为路径相邻节点。 | FR12 | BR-IMP-01/03/07/08 | UC-14 | SRS V2.1 P295 | 是 | NOT RUN |
| 39 | `AC-IMPL-FR12-02` | Given甲乙合格共同论文P有两个来源，When查看基础网络，Then同一成果仅贡献一次关系强度且边可回溯P。 | FR12 | BR-IMP-01/03/07/08 | UC-14 | SRS V2.1 P296 | 是 | NOT RUN |
| 40 | `AC-IMPL-FR12-03` | Given同一学者对拥有两项不同且合格的共同成果，When查看基础网络，Then只形成一条无向边且强度累计为2，显示两项证据。 | FR12 | BR-IMP-01/03/07/08 | UC-14 | SRS V2.1 P297 | 是 | NOT RUN |
| 41 | `AC-IMPL-FR12-04` | Given只同机构/同领域或存在待核验/已排除关系，When构图，Then不产生合作边，也不进入IN01论文子图。 | FR12 | BR-IMP-01/03/07/08 | UC-14 | SRS V2.1 P298 | 是 | NOT RUN |
| 42 | `AC-IMPL-FR12-05` | Given只有共同专利而无共同论文，When查询IN01，Then不据专利边计算论文最短路径。 | FR12 | BR-IMP-01/03/07/08 | UC-14 | SRS V2.1 P299 | 是 | NOT RUN |
| 43 | `AC-IMPL-FR13-01` | Given 2024–25关键词AI出现3次、CV出现2次，When 请求热点，Then 输出3与2并披露样本与时间窗。 | FR13 | BR-IMP-06/07 | UC-13 | SRS V2.1 P304 | 否 | 计划验收，未执行 |
| 44 | `AC-IMPL-FR13-02` | Given 数据稀疏或没有关键词，When 查询，Then 显示无数据/样本有限，不编造学术质量结论。 | FR13 | BR-IMP-06/07 | UC-13 | SRS V2.1 P305 | 否 | 计划验收，未执行 |
| 45 | `AC-IMPL-FR14-01` | Given 普通登录用户直接访问管理操作，When 提交，Then 拒绝且不产生业务变更。 | FR14 | BR-IMP-05/10 | UC-17 | SRS V2.1 P310 | 否 | 计划验收，未执行 |
| 46 | `AC-IMPL-FR14-02` | Given 管理员批准关系修正，When 查询审计，Then 可定位操作者、对象、时间、前后值、理由且不可由普通用户查看证明原文。 | FR14 | BR-IMP-05/10 | UC-17 | SRS V2.1 P311 | 否 | 计划验收，未执行 |
| 47 | `AC-IMPL-FR15-01` | Given 两个同名王伟无一致稳定证据，When 导入，Then 不自动并成一人，成果关系进入候选。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P316 | 否 | 计划验收，未执行 |
| 48 | `AC-IMPL-FR15-02` | Given 经审核改归属，When 查看门户和统计，Then 采用生效关系且来源/审计可追溯。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P317 | 否 | 计划验收，未执行 |
| 49 | `AC-IMPL-FR15-03` | Given同名异人、同人不同名且证据不足，When导入，Then不自动确认为同人、待人工核验；管理员审批事件不等于本人确认。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P318 | 否 | NOT RUN |
| 50 | `AC-IMPL-FR15-04` | Given已排除的错误作者关系，When再次采集，Then不得自动恢复为已确认或可统计合作的生效关系。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P319 | 否 | NOT RUN |
| 51 | `AC-IMPL-FR15-05` | Given成果归属修正正式生效，When更新下游，Then统计、门户及受影响的基础网络和IN01论文路径均采用生效关系并保留撤销审计。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P320 | 否 | NOT RUN |
| 52 | `AC-IMPL-FR15-06` | Given学者确认其中一条成果关联且另有N条自动候选署名，When查询或重采，Then其余N条不能因单次确认自动升级为本人确认。 | FR15 | BR-IMP-03/10 | UC-10、UC-08 | SRS V2.1 P321 | 否 | NOT RUN |
| 53 | `AC-IMPL-FR16-01` | Given 四类各有一条可公开样例，When 分别检索详情，Then 四类可识别且显示合法来源。 | FR16 | BR-IMP-02/07 | UC-16、UC-02 | SRS V2.1 P326 | 规模 | 计划验收，未执行 |
| 54 | `AC-IMPL-FR16-02` | Given 一成果跨两个来源且有版本关联，When 做规模核对，Then 按去重实体不按来源数/版本数计；真实数单列。 | FR16 | BR-IMP-02/07 | UC-16、UC-02 | SRS V2.1 P327 | 规模 | 计划验收，未执行 |
| 55 | `AC-IMPL-IN01-01` | Given 甲乙及乙丙各有一篇合格共同论文，When 甲查丙，Then 返回甲→乙→丙，两跳，分别展示可点击论文证据。 | IN01 | BR-IMP-08/09 | UC-15 | SRS V2.1 P335 | 否 | 计划验收，未执行 |
| 56 | `AC-IMPL-IN01-02` | Given 存在两条等长最短路径，When 固定图版本和查询条件，Then 根据学者稳定标识字典序返回同一条并说明此决定规则。 | IN01 | BR-IMP-08/09 | UC-15 | SRS V2.1 P336 | 否 | 计划验收，未执行 |
| 57 | `AC-IMPL-IN01-03` | Given 筛选导致断开或节点无效，When 查询，Then 提示当前范围无路径/节点不可用，不产生跨范围推断。 | IN01 | BR-IMP-08/09 | UC-15 | SRS V2.1 P337 | 否 | 计划验收，未执行 |
| 58 | `AC-IMPL-IN01-04` | Given论文合格子图≤1万节点且≤10万边，When执行约定筛选/路径查询负载，Then路径P95≤2秒，保存图版本、查询集和P50/P95/P99；超限返回可观察的超规模分支。 | IN01 | BR-IMP-08/09 | UC-15 | SRS V2.1 P338 | 否 | NOT RUN |
| 59 | `AC-IMPL-NF01-01` | 分别准备空库、失败和无权请求，页面不能混淆成功零条与执行失败。 | NF01 | BR-IMP-01 | UC-01、UC-02、UC-03 | SRS V2.1 P437 | 否 | NOT RUN |
| 60 | `AC-IMPL-NF02-01` | Given固定部署、≥500万可检索去重实体及100活跃用户查询集，When预热2分钟并以闭环负载测量≥5分钟，Then记录全部响应分位数、吞吐、错误率、硬件与索引证据，按已批准阈值判定，未执行为NOT RUN。 | NF02 | BR-IMP-01/04/07 | UC-01、UC-02、UC-15 | SRS V2.1 P440 | 规模 | NOT RUN |
| 61 | `AC-IMPL-NF02-02` | Given≥500万可检索去重成果、100活跃闭环并发、2分钟预热和≥5分钟测量，When执行固定查询集，Then请求错误率≤1%，单请求超时5秒，未超容量时记录全部P50/P95/P99和资源条件；无运行证据为NOT RUN。 | NF02 | BR-IMP-01/04/07 | UC-01、UC-02、UC-15 | SRS V2.1 P441 | 规模 | NOT RUN |
| 62 | `AC-IMPL-NF03-01` | 跨用户写入、停用账号、未授权读取审核材料均拒绝。 | NF03 | BR-IMP-05/11 | UC-04、UC-05、UC-06、UC-07、UC-08、UC-17 | SRS V2.1 P444 | 否 | NOT RUN |
| 63 | `AC-IMPL-NF04-01` | 恢复操作前后去重实体数/来源可核对，失败不删除历史。 | NF04 | BR-IMP-02/04 | UC-18、UC-19 | SRS V2.1 P447 | 否 | NOT RUN |
| 64 | `AC-IMPL-NF04-02` | Given一份可复核备份和故障恢复场景，When执行恢复，ThenRTO≤30分钟且RPO≤24小时，复核实体数、有效出处数、审核历史并保留日志。 | NF04 | BR-IMP-02/04 | UC-18、UC-19 | SRS V2.1 P448 | 否 | NOT RUN |
| 65 | `AC-IMPL-NF05-01` | Given有效归属变更正式生效，When逐视图查询，Then详情、检索、学者门户、统计和受影响的合作网络最终使用同一生效数据版本；逐视图记录传播时间，不得展示待审核关系。 | NF05 | BR-IMP-04/10 | UC-08、UC-14、UC-15 | SRS V2.1 P451 | 否 | NOT RUN |
| 66 | `AC-IMPL-NF05-02` | Given已审核的成果归属变更，When提交生效，Then检索、门户、统计和由论文参与关系派生的IN01/FR12网络最终一致；未运行标NOT RUN。 | NF05 | BR-IMP-04/10 | UC-08、UC-14、UC-15 | SRS V2.1 P452 | 否 | NOT RUN |
| 67 | `AC-IMPL-NF06-01` | 普通用户打开其他申请证明时被拒绝；公开详情无私有证明原文。 | NF06 | BR-IMP-11 | UC-03、UC-05、UC-06、UC-22 | SRS V2.1 P455 | 否 | NOT RUN |
| 68 | `AC-IMPL-NF07-01` | 停用来源后既有公开成果仍可读，管理员可从日志定位任务错误。 | NF07 | BR-IMP-04 | UC-18、UC-19 | SRS V2.1 P458 | 否 | NOT RUN |
| 69 | `AC-IMPL-NF08-01` | 检查三个被选模块各自独立覆盖率，不以平均值替代；工程证据由 CodeArts 实测产生。 | NF08 | BR-IMP-10 | 全仓实现与测试 | SRS V2.1 P461 | 否 | NOT RUN |

## 计数与排除规则

- 仅统计以 `AC-IMPL-<父项>-NN` 为独立定义键、带 Given/When/Then（NF01/NF03/NF06/NF07/NF08为可观察判据句）的条目。
- SPEC 的 T01–T42、SRS 的 `IN01-R1～R3`、BR-IMP 规则、历史 `AC-FRxx-Syy`、表格引用和章节范围文字均不重复计为 AC。
- `AC-IMPL-FR09-03` 在原文中位于 04–06 之后，保留原编号，不按出现顺序重排。
- V2.2 仅对非 AC 规模描述作最小同步；69 条 AC 的验收义务与编号不改。FR16 容量描述按用户最终决议拆为功能联调100万与最终容量500万。

## 来源与效力

- 69 条原文均逐条来自上述 DOCX；`source_location` 在 JSON 中保留原段落位置。
- 2026-10-20 用户最终决议确认 69 条为唯一权威验收基线、A1c 两项范围与 100万/500万/100并发。
- 原 59 条快照保留在历史文件 `../CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md`（带 HISTORICAL 标识）；Git 已提交 46 条保留在历史提交 `9a2609359da0aa6cc719d15c305471c5aa025879`。
- 本清单不声称 69 条测试通过；仅完成需求基线与课程分析输入的统一。
