// CognitaNexus 图投影 schema（Neo4j 5.x，Community 与 Enterprise 通用）
// 依据：ADR-005、架构设计说明书 §9.3、docs/ops/huaweicloud-migration-prep.md
//
// 文件由 Neo4jSchemaInitializer 在服务启动时逐条执行（cn.query.graph.enabled=true 才装配），
// 运维也可直接交给 cypher-shell 人工核对：
//   cypher-shell -a "$CN_NEO4J_URI" -u "$CN_NEO4J_USER" -p "$CN_NEO4J_PASSWORD" < schema.cypher
//
// 约定：
//   1. 只允许 CREATE ... IF NOT EXISTS：启动会重复执行，任何非幂等语句都会让第二次启动失败；
//   2. 一条语句一行，分号结尾；注释只允许整行 // 形式；
//   3. 这里不写 DROP。删除索引/约束属于运维变更，必须走单独评审，不能藏在启动流程里；
//   4. 图是派生数据：约束与索引丢失后可以全量从 PG 重建，但重建期间查询必须降级到 PG。

// ---------------------------------------------------------------------------
// Scholar：学者节点
//   scholar_id 是唯一键（全库唯一 ID 字符串，不转数字以避免精度与区域排序问题）。
//   domain / organization / data_origin 是公开网络最常用的三个筛选维度；
//   data_origin 与成果的 data_nature 同义，用于 REAL / SIMULATED 隔离。
// ---------------------------------------------------------------------------
CREATE CONSTRAINT scholar_id_unique IF NOT EXISTS
FOR (s:Scholar) REQUIRE s.scholar_id IS UNIQUE;

CREATE INDEX scholar_domain IF NOT EXISTS
FOR (s:Scholar) ON (s.domain);

CREATE INDEX scholar_organization IF NOT EXISTS
FOR (s:Scholar) ON (s.organization);

CREATE INDEX scholar_data_origin IF NOT EXISTS
FOR (s:Scholar) ON (s.data_origin);

// ---------------------------------------------------------------------------
// Work：合格学术成果节点
//   is_public=false 是墓碑：成果曾投影过但已不再公开，保留它以追溯来源版本，
//   任何图查询都必须显式过滤 is_public=true。
//   source_version 用于幂等与防乱序：只接受不更旧的版本。
//   edges_materialized 标记该成果的作者对边是否已物化（高作者数成果只在二部图中推导）。
// ---------------------------------------------------------------------------
CREATE CONSTRAINT work_id_unique IF NOT EXISTS
FOR (w:Work) REQUIRE w.work_id IS UNIQUE;

CREATE INDEX work_public IF NOT EXISTS
FOR (w:Work) ON (w.is_public);

CREATE INDEX work_data_origin IF NOT EXISTS
FOR (w:Work) ON (w.data_origin);

CREATE INDEX work_source_version IF NOT EXISTS
FOR (w:Work) ON (w.source_version);

CREATE INDEX work_edges_materialized IF NOT EXISTS
FOR (w:Work) ON (w.edges_materialized);

// ---------------------------------------------------------------------------
// AUTHORED：学者参与成果
//   关系属性：relation_state（AUTO / SELF_CONFIRMED）、author_order、work_id、work_title。
//   work_id 上建关系属性索引，使「按成果整体替换」时的删除与证据查询不必全图扫描。
// ---------------------------------------------------------------------------
CREATE INDEX authored_work_id IF NOT EXISTS
FOR ()-[a:AUTHORED]-() ON (a.work_id);

// ---------------------------------------------------------------------------
// COOPERATED_WITH：满足业务条件的合作关系
//   每条关系**只代表一篇共同合格成果**，携带 work_id 与 work_title 作为证据，
//   边强度由 count(DISTINCT work_id) 得出（与 qry_coauthor_edge 口径一致），
//   不为所有作者组合生成无证据的邻接。
//   pair_key 是两端规范 ID 的固定字典序拼接，用于对账与按作者对检索。
// ---------------------------------------------------------------------------
CREATE INDEX cooperated_work_id IF NOT EXISTS
FOR ()-[c:COOPERATED_WITH]-() ON (c.work_id);

CREATE INDEX cooperated_pair_key IF NOT EXISTS
FOR ()-[c:COOPERATED_WITH]-() ON (c.pair_key);

CREATE INDEX cooperated_data_origin IF NOT EXISTS
FOR ()-[c:COOPERATED_WITH]-() ON (c.data_origin);

// ---------------------------------------------------------------------------
// GraphProjectionMeta：投影水位
//   scope 唯一，记录已投影到的版本与对应 PG generation。水位只前进不后退；
//   图水位落后于 PG 已发布修订时，公开查询必须降级回 PG，不能拿旧图当当前结果。
// ---------------------------------------------------------------------------
CREATE CONSTRAINT graph_projection_meta_scope_unique IF NOT EXISTS
FOR (m:GraphProjectionMeta) REQUIRE m.scope IS UNIQUE;
