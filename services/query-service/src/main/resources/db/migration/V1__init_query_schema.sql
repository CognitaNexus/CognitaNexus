-- =============================================================================
-- CognitaNexus query-service 迁移 V1：读投影、Inbox、发布水位、合作边
-- 依据：架构 V0.2 §5.2（query 分域 read/graph/evt）、§6.3（Outbox/Inbox）、
--       §7.1（发布协议与 generation）、§7.3（重建与原子切换）、§9（检索/统计/图）
--
-- 设计要点：
--   1. 全部投影表带 generation 列并进入主键。普通增量更新只写当前活动 generation；
--      全量重建写新 generation，核对后由一行 UPDATE 原子切换（§7.1、§7.3）。
--      没有这一列就只能原地重建，而原地重建期间查询必然读到半成品。
--   2. Inbox 事件正文只追加，(consumer_id, event_id) 唯一键消除重复投递的业务效果（§6.3）。
--      processing_state 可变，用于区分「已接收未应用」与「已应用」，不删除事件正文。
--   3. received_revision 与 published_revision 分开存放。前者表示事件已持久接收到的连续水位，
--      后者表示投影已发布的连续水位；两者不相等时公开查询必须拒绝，而不是拿旧版本冒充新版本。
--   4. 合作边按 (generation, achievement_id, scholar_a, scholar_b) 落库，使得同一条边在论文
--      更新时先删后插仍然幂等，边强度 = 不同合格论文数（§9.3）。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

COMMENT ON EXTENSION pg_trgm IS
    '三元组索引用于候选生成与关键词匹配（架构 §9.1）。中文分词与相关性排序不由此扩展决定。';

-- -----------------------------------------------------------------------------
-- 发布水位与 generation
-- -----------------------------------------------------------------------------
CREATE TABLE qry_generation (
    generation  text        PRIMARY KEY,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    ready_at    timestamptz,
    note        text,
    CONSTRAINT ck_qry_generation_status CHECK (status IN ('BUILDING', 'READY', 'RETIRED', 'FAILED'))
);
COMMENT ON TABLE qry_generation IS
    '投影 generation。重建先建 BUILDING，核对通过转 READY 并切换，旧代转 RETIRED 后按保留窗口清理'
    '（架构 §7.1、§7.3）。RETIRED 不立即删除，避免在途请求读到已被清空的代。';

CREATE TABLE qry_consumer_state (
    consumer_id           text        PRIMARY KEY,
    active_generation     text        NOT NULL REFERENCES qry_generation (generation),
    received_revision     bigint      NOT NULL DEFAULT 0,
    highest_seen_revision bigint      NOT NULL DEFAULT 0,
    published_revision    bigint      NOT NULL DEFAULT 0,
    received_at           timestamptz,
    published_at          timestamptz,
    updated_at            timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE qry_consumer_state IS
    '每接收者一组水位。receivedRevision 只随连续修订推进，缺口期不得跳过毒事件伪造新水位；'
    'publishedRevision 表示投影已发布到该修订（架构 §6.3）。';

INSERT INTO qry_generation (generation, status, ready_at, note)
VALUES ('gen0001', 'READY', now(), '初始空投影：随首次事件增量填充');

INSERT INTO qry_consumer_state (consumer_id, active_generation, received_revision, published_revision)
VALUES ('query', 'gen0001', 0, 0);

-- -----------------------------------------------------------------------------
-- Inbox 事件
-- -----------------------------------------------------------------------------
CREATE TABLE qry_inbox_event (
    id                text        PRIMARY KEY,
    consumer_id       text        NOT NULL,
    event_id          text        NOT NULL,
    event_type        text        NOT NULL,
    schema_version    integer     NOT NULL,
    aggregate_type    text        NOT NULL,
    aggregate_id      text        NOT NULL,
    aggregate_version bigint      NOT NULL,
    catalog_revision  bigint      NOT NULL,
    occurred_at       timestamptz,
    trace_id          text,
    payload           jsonb       NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now(),
    processing_state  text        NOT NULL DEFAULT 'RECEIVED',
    attempts          integer     NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL DEFAULT now(),
    applied_at        timestamptz,
    last_error        text,
    CONSTRAINT uk_qry_inbox_consumer_event UNIQUE (consumer_id, event_id),
    CONSTRAINT ck_qry_inbox_state CHECK (
        processing_state IN ('RECEIVED', 'APPLIED', 'ISOLATED', 'FAILED')
    )
);
COMMENT ON TABLE qry_inbox_event IS
    '持久 Inbox：只在提交后 ACK，因此重复投递必然到达。唯一键 (consumer_id, event_id) 保证重投'
    '不产生重复业务效果。未知 schemaVersion 转 ISOLATED 待处理，不丢弃后继续宣称全量同步。';
COMMENT ON COLUMN qry_inbox_event.attempts IS
    '投影应用尝试次数。与投递重试分开计数：投递成功不代表投影成功，两者混在一个计数里会让'
    '「事件已收到但投影反复失败」看起来像网络问题。';

CREATE INDEX ix_qry_inbox_revision ON qry_inbox_event (consumer_id, catalog_revision);
CREATE INDEX ix_qry_inbox_state ON qry_inbox_event (consumer_id, processing_state);

-- -----------------------------------------------------------------------------
-- 读投影：成果
-- -----------------------------------------------------------------------------
CREATE TABLE qry_achievement (
    generation        text        NOT NULL,
    achievement_id    text        NOT NULL,
    type              text        NOT NULL,
    title             text        NOT NULL,
    title_norm        text        NOT NULL,
    data_nature       text        NOT NULL,
    visibility        text        NOT NULL,
    effective_status  text        NOT NULL,
    publication_year  integer,
    doi               text,
    venue             text,
    citation_count    bigint,
    citation_source   text,
    published_date    date,
    abstract_text     text,
    aggregate_version bigint      NOT NULL,
    revision          bigint      NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_qry_achievement PRIMARY KEY (generation, achievement_id),
    CONSTRAINT ck_qry_achievement_nature CHECK (data_nature IN ('REAL', 'SIMULATED', 'MIXED')),
    CONSTRAINT ck_qry_achievement_visibility CHECK (
        visibility IN ('PUBLIC', 'RESTRICTED', 'PRIVATE')
    ),
    CONSTRAINT ck_qry_achievement_type CHECK (
        type IN ('PAPER', 'PATENT', 'PROJECT', 'AWARD')
    ),
    CONSTRAINT ck_qry_achievement_year CHECK (publication_year IS NULL OR publication_year > 0)
);
COMMENT ON TABLE qry_achievement IS
    '成果公开投影。年份可为 NULL：来源未给出年份时不得补 1 月 1 日或当前时间（架构 §5.4）。'
    'dataNature 与 core 一致，REAL/SIMULATED/MIXED 互斥；默认现实统计只接受 REAL。';

CREATE INDEX ix_qry_achievement_year ON qry_achievement (generation, publication_year);
CREATE INDEX ix_qry_achievement_type ON qry_achievement (generation, type);
CREATE INDEX ix_qry_achievement_nature ON qry_achievement (generation, data_nature);
CREATE INDEX ix_qry_achievement_eligible ON qry_achievement (generation, effective_status, visibility);
CREATE INDEX ix_qry_achievement_title_trgm
    ON qry_achievement USING gin (title_norm gin_trgm_ops);
CREATE INDEX ix_qry_achievement_revision ON qry_achievement (generation, revision);

-- -----------------------------------------------------------------------------
-- 读投影：参与关系、发生时机构、领域
-- -----------------------------------------------------------------------------
CREATE TABLE qry_contribution (
    generation     text    NOT NULL,
    achievement_id text    NOT NULL,
    scholar_id     text    NOT NULL,
    scholar_name   text,
    role           text    NOT NULL,
    author_order   integer,
    relation_state text    NOT NULL,
    CONSTRAINT pk_qry_contribution PRIMARY KEY (generation, achievement_id, scholar_id),
    CONSTRAINT ck_qry_contribution_state CHECK (
        relation_state IN ('AUTO', 'SELF_CONFIRMED', 'PENDING_VERIFY', 'DISPUTED', 'EXCLUDED')
    ),
    CONSTRAINT ck_qry_contribution_order CHECK (author_order IS NULL OR author_order >= 1)
);
COMMENT ON TABLE qry_contribution IS
    '同一学者对同一成果只有一行，多角色不复用本表重复计数（架构 §5.3）。'
    'relation_state 独立于成果是否公开：只有 AUTO 与 SELF_CONFIRMED 参与确定合作边（BR-IMP-08）。';

CREATE INDEX ix_qry_contribution_scholar ON qry_contribution (generation, scholar_id);
CREATE INDEX ix_qry_contribution_achievement ON qry_contribution (generation, achievement_id);

CREATE TABLE qry_contribution_org (
    generation     text NOT NULL,
    achievement_id text NOT NULL,
    scholar_id     text NOT NULL,
    organization   text NOT NULL,
    CONSTRAINT pk_qry_contribution_org
        PRIMARY KEY (generation, achievement_id, scholar_id, organization)
);
COMMENT ON TABLE qry_contribution_org IS
    '成果发生时的机构归属，可零个或多个。统计按「同成果同机构只计一次」去重（架构 §9.2）；'
    '缺失即未知，不进入已知机构排名，也不用学者当前任职机构补齐。';

CREATE INDEX ix_qry_contribution_org_name ON qry_contribution_org (generation, organization);

CREATE TABLE qry_achievement_topic (
    generation     text NOT NULL,
    achievement_id text NOT NULL,
    topic_id       text NOT NULL,
    canonical_name text,
    CONSTRAINT pk_qry_achievement_topic PRIMARY KEY (generation, achievement_id, topic_id)
);
COMMENT ON TABLE qry_achievement_topic IS
    '领域为多对多且互不排斥，多选查询的合计可超过总量，统计响应必须明示这一点（架构 §9.2）。';

CREATE INDEX ix_qry_achievement_topic_topic ON qry_achievement_topic (generation, topic_id);

-- -----------------------------------------------------------------------------
-- 合作边：论文 → 作者对贡献 → 边 → 证据
-- -----------------------------------------------------------------------------
CREATE TABLE qry_coauthor_edge (
    generation     text NOT NULL,
    achievement_id text NOT NULL,
    scholar_a      text NOT NULL,
    scholar_b      text NOT NULL,
    CONSTRAINT pk_qry_coauthor_edge PRIMARY KEY (generation, achievement_id, scholar_a, scholar_b),
    CONSTRAINT ck_qry_coauthor_canonical_order CHECK (scholar_a < scholar_b)
);
COMMENT ON TABLE qry_coauthor_edge IS
    '仅真实、公开、生效、关系合格的共同论文建无向边（架构 §9.3）。以规范 ID 字符串的固定字典序'
    '存放两端，避免同一对作者因写入顺序不同产生两条边。边强度由 count(distinct achievement_id) 得出，'
    '论文更新时先删本论文的全部边再重新插入，不靠累计加一。';

CREATE INDEX ix_qry_coauthor_edge_a ON qry_coauthor_edge (generation, scholar_a);
CREATE INDEX ix_qry_coauthor_edge_b ON qry_coauthor_edge (generation, scholar_b);

-- -----------------------------------------------------------------------------
-- 合并重定向
-- -----------------------------------------------------------------------------
CREATE TABLE qry_id_redirect (
    entity_type text   NOT NULL,
    old_id      text   NOT NULL,
    new_id      text   NOT NULL,
    revision    bigint NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_qry_id_redirect PRIMARY KEY (entity_type, old_id)
);
COMMENT ON TABLE qry_id_redirect IS
    '合并后旧标识跳到幸存实体，旧链接不得指向错误对象（架构 §5.4）。重定向与 generation 无关，'
    '因此不随重建清空：历史链接的可用性不应取决于当前投影属于哪一代。';

-- -----------------------------------------------------------------------------
-- 长任务：重建运行记录
-- -----------------------------------------------------------------------------
CREATE TABLE qry_rebuild_run (
    id                text        PRIMARY KEY,
    generation        text        NOT NULL,
    status            text        NOT NULL,
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    base_revision     bigint,
    replayed_revision bigint,
    imported_rows     bigint      NOT NULL DEFAULT 0,
    note              text,
    CONSTRAINT ck_qry_rebuild_status CHECK (
        status IN ('RUNNING', 'SWITCHED', 'ABORTED')
    )
);
COMMENT ON TABLE qry_rebuild_run IS
    '重建运行记录。baseRevision 是导出快照的 catalogRevision，replayedRevision 是回放事件后的水位；'
    '两者之间的差额说明重建期间发生过变更，必须回放补齐后才允许切换（架构 §7.3）。';
