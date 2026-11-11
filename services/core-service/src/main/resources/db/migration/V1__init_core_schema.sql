-- =============================================================================
-- CognitaNexus core-service 初始迁移
-- 权威数据：账号与会话撤销、门户授权、学者、成果、来源事实、参与关系、审核决定、
--           分类映射、审计、Outbox 变更集
-- 依据：架构 V0.2 第 5 节（数据模型与业务不变量）、第 6 节（事务与事件）
-- 约定：表名带分域前缀；约束前缀 pk_/fk_/uk_/ck_/ix_
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ref 参考域：领域（分类）与映射
-- -----------------------------------------------------------------------------
CREATE TABLE ref_topic (
    id              text        PRIMARY KEY,
    canonical_name  text        NOT NULL,
    name_norm       text        NOT NULL,
    source_category text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_ref_topic_norm UNIQUE (name_norm)
);
COMMENT ON TABLE ref_topic IS '领域/分类规范词。来源原分类保留在 source_category，不覆盖。';

CREATE TABLE ref_topic_alias (
    id          text        PRIMARY KEY,
    alias_norm  text        NOT NULL,
    topic_id    text        NOT NULL REFERENCES ref_topic (id),
    evidence    text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_ref_topic_alias_norm UNIQUE (alias_norm),
    CONSTRAINT ck_ref_topic_alias_evidence CHECK (evidence IS NOT NULL AND btrim(evidence) <> '')
);
COMMENT ON TABLE ref_topic_alias IS '同义词映射。依据 FR13：同义词合并必须有凭证，无凭证不得合并。';

CREATE TABLE ref_organization (
    id             text        PRIMARY KEY,
    canonical_name text        NOT NULL,
    name_norm      text        NOT NULL,
    country_code   text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_ref_organization_norm UNIQUE (name_norm)
);

-- -----------------------------------------------------------------------------
-- iam 身份域：账号、角色、能力、门户授权、会话撤销
-- 账号与学者档案分离建模：账号停用不得删除学者公开档案（AC-IMPL-FR04-02）
-- -----------------------------------------------------------------------------
CREATE TABLE iam_account (
    id            text        PRIMARY KEY,
    username      text        NOT NULL,
    username_norm text        NOT NULL,
    password_hash text        NOT NULL,
    display_name  text        NOT NULL,
    status        text        NOT NULL,
    auth_version  bigint      NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_iam_account_username UNIQUE (username_norm),
    CONSTRAINT ck_iam_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
COMMENT ON COLUMN iam_account.auth_version IS '停用/撤销授权时递增，使既有会话即使仍在 Redis 也不可用（架构 §10.2）。';

CREATE TABLE iam_account_role (
    account_id text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    role       text        NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_iam_account_role PRIMARY KEY (account_id, role),
    CONSTRAINT ck_iam_account_role_role CHECK (role IN ('USER', 'REVIEWER', 'PLATFORM_ADMIN'))
);

CREATE TABLE iam_portal_authority (
    id                    text        PRIMARY KEY,
    account_id            text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    scholar_id            text        NOT NULL,
    status                text        NOT NULL,
    claim_request_id      text,
    granted_at            timestamptz NOT NULL,
    granted_by_account_id text        REFERENCES iam_account (id) ON DELETE RESTRICT,
    revoked_at            timestamptz,
    revoke_reason         text,
    CONSTRAINT ck_iam_portal_authority_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_iam_portal_authority_revoked CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status = 'ACTIVE' AND revoked_at IS NULL)
    )
);
COMMENT ON TABLE iam_portal_authority IS
    '对象级门户授权。一个学者门户任一时刻至多一个生效授权；竞争认领不自动转移原授权（AC-IMPL-FR05-03）。';
CREATE UNIQUE INDEX uk_iam_portal_authority_active
    ON iam_portal_authority (scholar_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_iam_portal_authority_account ON iam_portal_authority (account_id) WHERE status = 'ACTIVE';

CREATE TABLE iam_session_revocation (
    session_id_hash text        PRIMARY KEY,
    account_id      text        NOT NULL,
    revoked_at      timestamptz NOT NULL DEFAULT now(),
    reason          text        NOT NULL,
    CONSTRAINT ck_iam_session_revocation_reason CHECK (reason IN ('LOGOUT', 'ACCOUNT_DISABLED', 'ADMIN_REVOKE'))
);
COMMENT ON TABLE iam_session_revocation IS
    '会话标识的不可逆摘要。退出先持久登记撤销再清 Redis 会话，避免 Redis 恢复旧数据后会话复活（架构 §10.2）。';

-- -----------------------------------------------------------------------------
-- raw 来源域：来源配置、原始来源版本
-- 策略：原值只追加；幂等重复不改原值（架构 §5.2）
-- -----------------------------------------------------------------------------
CREATE TABLE raw_source_config (
    id              text        PRIMARY KEY,
    source_code     text        NOT NULL,
    display_name    text        NOT NULL,
    enabled         boolean     NOT NULL DEFAULT false,
    license_scope   text        NOT NULL,
    base_url        text        NOT NULL,
    config_version  integer     NOT NULL DEFAULT 1,
    allowed_fields  jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_raw_source_config_code UNIQUE (source_code),
    CONSTRAINT ck_raw_source_config_license CHECK (btrim(license_scope) <> '')
);
COMMENT ON COLUMN raw_source_config.allowed_fields IS
    '字段白名单。适配器只保留白名单内字段，不以「保留原始响应」为由持久化意外返回的敏感信息（架构 §6.2）。';

CREATE TABLE raw_source_record (
    id                  text        PRIMARY KEY,
    source_id           text        NOT NULL REFERENCES raw_source_config (id) ON DELETE RESTRICT,
    source_record_id    text        NOT NULL,
    content_fingerprint text        NOT NULL,
    payload             jsonb       NOT NULL,
    data_nature         text        NOT NULL,
    source_updated_at   timestamptz,
    source_published_at date,
    fetched_at          timestamptz NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    catalog_revision    bigint      NOT NULL,
    first_received_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_raw_source_record_version
        UNIQUE (source_id, source_record_id, content_fingerprint),
    CONSTRAINT ck_raw_source_record_nature CHECK (data_nature IN ('REAL', 'SIMULATED', 'MIXED'))
);
COMMENT ON TABLE raw_source_record IS
    '原始来源版本，只追加。幂等键 sourceId + sourceRecordId + contentFingerprint；'
    '来源记录相同但接收时间不同不是新业务事实，重复交接返回原接收结果（架构 §5.3、§6.2）。';
CREATE INDEX ix_raw_source_record_source ON raw_source_record (source_id, source_record_id);

-- -----------------------------------------------------------------------------
-- core 规范域：成果、学者、类型扩展、关系、领域、机构归属
-- -----------------------------------------------------------------------------
CREATE TABLE core_achievement (
    id                text        PRIMARY KEY,
    type              text        NOT NULL,
    title             text        NOT NULL,
    title_norm        text        NOT NULL,
    data_nature       text        NOT NULL,
    visibility        text        NOT NULL DEFAULT 'RESTRICTED',
    effective_status  text        NOT NULL DEFAULT 'PENDING_GOVERNANCE',
    publication_year  smallint,
    year_precision    text,
    merged_into_id    text        REFERENCES core_achievement (id) ON DELETE RESTRICT,
    version           bigint      NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_core_achievement_type CHECK (type IN ('PAPER', 'PATENT', 'PROJECT', 'AWARD')),
    CONSTRAINT ck_core_achievement_nature CHECK (data_nature IN ('REAL', 'SIMULATED', 'MIXED')),
    CONSTRAINT ck_core_achievement_visibility CHECK (visibility IN ('PUBLIC', 'RESTRICTED', 'HIDDEN')),
    CONSTRAINT ck_core_achievement_status CHECK (
        effective_status IN ('PENDING_GOVERNANCE', 'EFFECTIVE', 'WITHDRAWN')
    ),
    CONSTRAINT ck_core_achievement_year_precision CHECK (
        (publication_year IS NULL AND year_precision IS NULL)
        OR (publication_year IS NOT NULL AND year_precision IN ('YEAR', 'UNKNOWN'))
    )
);
COMMENT ON COLUMN core_achievement.publication_year IS
    '年份使用年份值，不补 1 月 1 日假装完整日期（架构 §5.4、编码规范 §4.5）。';
CREATE INDEX ix_core_achievement_public ON core_achievement (visibility, effective_status, type, publication_year);
CREATE INDEX ix_core_achievement_title_norm ON core_achievement (title_norm);

CREATE TABLE core_paper (
    achievement_id  text        PRIMARY KEY REFERENCES core_achievement (id) ON DELETE RESTRICT,
    doi             text,
    doi_norm        text,
    venue           text,
    published_date   date,
    preprint_status text,
    citation_count  integer,
    citation_source text,
    abstract_text   text,
    CONSTRAINT ck_core_paper_preprint CHECK (
        preprint_status IS NULL OR preprint_status IN ('PREPRINT', 'PUBLISHED', 'UNKNOWN')
    ),
    CONSTRAINT ck_core_paper_citation CHECK (citation_count IS NULL OR citation_count >= 0)
);
-- DOI 归一后唯一：同一 DOI 的两个相容来源必须归并为一个成果（AC-IMPL-FR09-01）
CREATE UNIQUE INDEX uk_core_paper_doi_norm ON core_paper (doi_norm) WHERE doi_norm IS NOT NULL;

CREATE TABLE core_patent (
    achievement_id      text        PRIMARY KEY REFERENCES core_achievement (id) ON DELETE RESTRICT,
    application_no      text,
    application_no_norm text,
    publication_no      text,
    ipc_codes           text[],
    applicant_names     text[],
    filing_date         date,
    publication_date    date
);
-- 申请号在自身作用域内归一匹配；公开号不同不按标题合并（需求基线 5.1）
CREATE UNIQUE INDEX uk_core_patent_application_no_norm
    ON core_patent (application_no_norm) WHERE application_no_norm IS NOT NULL;

CREATE TABLE core_research_project (
    achievement_id    text        PRIMARY KEY REFERENCES core_achievement (id) ON DELETE RESTRICT,
    project_no        text,
    project_no_norm   text,
    leader_name       text,
    funding_agency    text,
    project_category  text,
    start_date        date,
    end_date          date
);
COMMENT ON COLUMN core_research_project.funding_agency IS
    '不默认采集或公开经费金额（SRS 5.1、BR-IMP-11）。';
CREATE UNIQUE INDEX uk_core_research_project_no_norm
    ON core_research_project (project_no_norm) WHERE project_no_norm IS NOT NULL;

CREATE TABLE core_award (
    achievement_id  text        PRIMARY KEY REFERENCES core_achievement (id) ON DELETE RESTRICT,
    award_name      text        NOT NULL,
    award_year      smallint,
    award_level     text,
    granting_body   text
);
COMMENT ON TABLE core_award IS '获奖人与获奖成果分别表达：人员通过参与关系承载，不塞进本表（SRS 5.1）。';

CREATE TABLE core_scholar (
    id                text        PRIMARY KEY,
    display_name      text        NOT NULL,
    name_norm         text        NOT NULL,
    data_nature       text        NOT NULL,
    claimed_status    text        NOT NULL DEFAULT 'UNCLAIMED',
    organization_name text,
    research_summary  text,
    merged_into_id    text        REFERENCES core_scholar (id) ON DELETE RESTRICT,
    version           bigint      NOT NULL DEFAULT 1,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_core_scholar_nature CHECK (data_nature IN ('REAL', 'SIMULATED', 'MIXED')),
    CONSTRAINT ck_core_scholar_claimed CHECK (
        claimed_status IN ('UNCLAIMED', 'CLAIM_PENDING', 'CLAIMED', 'CLAIM_REJECTED')
    )
);
COMMENT ON TABLE core_scholar IS
    '学者档案可先于账号存在；认领通过只授予管理权，不等于身份证明或成果真实性背书（SRS 5.4）。';
CREATE INDEX ix_core_scholar_name_norm ON core_scholar (name_norm);

CREATE TABLE core_scholar_name_variant (
    id          text        PRIMARY KEY,
    scholar_id  text        NOT NULL REFERENCES core_scholar (id) ON DELETE RESTRICT,
    variant     text        NOT NULL,
    variant_norm text       NOT NULL,
    evidence    text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_core_scholar_name_variant UNIQUE (scholar_id, variant_norm)
);
COMMENT ON TABLE core_scholar_name_variant IS
    '姓名别名。别名归一只产候选，同名不是同人证据（BR-IMP-03、AC-IMPL-FR15-01）。';

CREATE TABLE core_contribution (
    id               text        PRIMARY KEY,
    scholar_id       text        NOT NULL REFERENCES core_scholar (id) ON DELETE RESTRICT,
    achievement_id   text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    role             text        NOT NULL DEFAULT 'AUTHOR',
    author_order     integer,
    relation_state   text        NOT NULL,
    source_record_id text        REFERENCES raw_source_record (id) ON DELETE RESTRICT,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          bigint      NOT NULL DEFAULT 1,
    CONSTRAINT uk_core_contribution_scholar_achievement UNIQUE (scholar_id, achievement_id),
    CONSTRAINT ck_core_contribution_state CHECK (
        relation_state IN ('AUTO', 'SELF_CONFIRMED', 'PENDING_VERIFY', 'DISPUTED', 'EXCLUDED')
    ),
    CONSTRAINT ck_core_contribution_role CHECK (role IN ('AUTHOR', 'INVENTOR', 'LEADER', 'PARTICIPANT', 'AWARDEE')),
    CONSTRAINT ck_core_contribution_order CHECK (author_order IS NULL OR author_order >= 1)
);
COMMENT ON TABLE core_contribution IS
    '一个学者对同一成果只有一条参与关系；多角色在 role/子结构表达，不为每个角色重复计数（架构 §5.3）。'
    '关系状态独立存储，认领通过不使该学者全部成果变为已确认（SRS 5.2）。';
CREATE INDEX ix_core_contribution_achievement ON core_contribution (achievement_id);
CREATE INDEX ix_core_contribution_scholar ON core_contribution (scholar_id);

CREATE TABLE core_contribution_org (
    contribution_id text NOT NULL REFERENCES core_contribution (id) ON DELETE RESTRICT,
    organization_id text NOT NULL REFERENCES ref_organization (id) ON DELETE RESTRICT,
    CONSTRAINT pk_core_contribution_org PRIMARY KEY (contribution_id, organization_id)
);
COMMENT ON TABLE core_contribution_org IS
    '成果发生时的机构归属，可零个或多个；缺失标未知，不静默使用当前任职机构（BR-IMP-06、架构 §5.1）。';

CREATE TABLE core_achievement_topic (
    achievement_id text NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    topic_id       text NOT NULL REFERENCES ref_topic (id) ON DELETE RESTRICT,
    source_category text,
    CONSTRAINT pk_core_achievement_topic PRIMARY KEY (achievement_id, topic_id)
);
COMMENT ON TABLE core_achievement_topic IS
    '成果与领域多对多。保留来源原分类，无法可靠映射时标记待分类，不强行归类（SRS 5.3）。';

CREATE TABLE core_achievement_source (
    id                  text        PRIMARY KEY,
    achievement_id      text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    source_record_id    text        NOT NULL REFERENCES raw_source_record (id) ON DELETE RESTRICT,
    source_id           text        NOT NULL REFERENCES raw_source_config (id) ON DELETE RESTRICT,
    source_updated_at   timestamptz,
    received_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_core_achievement_source UNIQUE (achievement_id, source_record_id)
);
COMMENT ON TABLE core_achievement_source IS
    '一个成果实体可对应多个来源记录；跨源重复不增加成果实体数（AC-IMPL-FR16-02、BR-IMP-06）。';

-- 稳定标识注册：把「类型 + 标识种类 + 作用域 + 规范值」绑定到唯一规范实体。
-- 未经证实的冲突标识保留在来源域（冲突时插入失败 → 转为治理候选，而不是硬塞两个规范实体）。
CREATE TABLE core_identifier_registry (
    id               text        PRIMARY KEY,
    entity_type      text        NOT NULL,
    entity_id        text        NOT NULL,
    identifier_kind  text        NOT NULL,
    scope            text        NOT NULL DEFAULT '',
    normalized_value text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_core_identifier_registry UNIQUE (identifier_kind, scope, normalized_value),
    CONSTRAINT ck_core_identifier_registry_entity CHECK (entity_type IN ('ACHIEVEMENT', 'SCHOLAR')),
    CONSTRAINT ck_core_identifier_registry_kind CHECK (
        identifier_kind IN ('DOI', 'PATENT_APPLICATION_NO', 'PROJECT_NO', 'ORCID', 'OPENALEX_ID')
    )
);
COMMENT ON TABLE core_identifier_registry IS
    '稳定标识注册键 = 类型 + 标识种类 + 作用域 + 规范值（架构 §5.3）。冲突标识先形成治理候选，'
    '不强行插入两个冲突规范实体再靠唯一索引报错。';

-- 字段级溯源：原值、人工值与已审核值分别追溯
CREATE TABLE core_field_provenance (
    id               text        PRIMARY KEY,
    entity_type      text        NOT NULL,
    entity_id        text        NOT NULL,
    field_name       text        NOT NULL,
    value_level      text        NOT NULL,
    value_text       text,
    source_record_id text        REFERENCES raw_source_record (id) ON DELETE RESTRICT,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uk_core_field_provenance UNIQUE (entity_type, entity_id, field_name),
    CONSTRAINT ck_core_field_provenance_level CHECK (value_level IN ('SOURCE', 'MANUAL', 'REVIEWED')),
    CONSTRAINT ck_core_field_provenance_entity CHECK (entity_type IN ('ACHIEVEMENT', 'SCHOLAR'))
);
COMMENT ON TABLE core_field_provenance IS
    '自动更新只写 SOURCE 级；人工与已审核值不被自动覆盖（BR-IMP-04、AC-IMPL-FR10-01、架构 §5.3）。';

-- -----------------------------------------------------------------------------
-- gov 治理域：去重候选、合并拆分台账、异常
-- -----------------------------------------------------------------------------
CREATE TABLE gov_dedup_candidate (
    id                   text        PRIMARY KEY,
    achievement_type     text        NOT NULL,
    left_achievement_id  text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    right_achievement_id text        NOT NULL REFERENCES core_achievement (id) ON DELETE RESTRICT,
    evidence             jsonb       NOT NULL,
    status               text        NOT NULL DEFAULT 'OPEN',
    created_at           timestamptz NOT NULL DEFAULT now(),
    resolved_at          timestamptz,
    resolved_by          text,
    resolution_reason    text,
    CONSTRAINT uk_gov_dedup_candidate UNIQUE (left_achievement_id, right_achievement_id),
    CONSTRAINT ck_gov_dedup_candidate_status CHECK (status IN ('OPEN', 'MERGED', 'REJECTED')),
    CONSTRAINT ck_gov_dedup_candidate_distinct CHECK (left_achievement_id <> right_achievement_id)
);
COMMENT ON TABLE gov_dedup_candidate IS
    '仅标题相似不得自动合并，只产生候选，还须比对年份、参与者与类型（AC-IMPL-FR09-02）。';

CREATE TABLE gov_merge_ledger (
    id           text        PRIMARY KEY,
    operation    text        NOT NULL,
    survivor_id  text        NOT NULL,
    merged_id    text        NOT NULL,
    entity_type  text        NOT NULL,
    snapshot     jsonb       NOT NULL,
    reason       text        NOT NULL,
    operator_id  text        NOT NULL,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_gov_merge_ledger_operation CHECK (operation IN ('MERGE', 'SPLIT')),
    CONSTRAINT ck_gov_merge_ledger_entity CHECK (entity_type IN ('ACHIEVEMENT', 'SCHOLAR'))
);
COMMENT ON TABLE gov_merge_ledger IS
    '合并/拆分留痕：旧 ID 重定向、来源归属与关系前后快照，旧链接不得指向错误对象（架构 §5.4）。只追加。';

CREATE TABLE gov_anomaly (
    id               text        PRIMARY KEY,
    kind             text        NOT NULL,
    source_record_id text        REFERENCES raw_source_record (id) ON DELETE RESTRICT,
    entity_id        text,
    detail           jsonb       NOT NULL,
    status           text        NOT NULL DEFAULT 'OPEN',
    created_at       timestamptz NOT NULL DEFAULT now(),
    resolved_at      timestamptz,
    CONSTRAINT ck_gov_anomaly_status CHECK (status IN ('OPEN', 'RESOLVED'))
);
COMMENT ON TABLE gov_anomaly IS
    '异常不直接成为公开成果；待核验与异常数量在任务结果中单列披露（AC-IMPL-FR08-01）。';

-- -----------------------------------------------------------------------------
-- wf 流程域：认领申请、材料、门户变更申请
-- -----------------------------------------------------------------------------
CREATE TABLE wf_claim_request (
    id               text        PRIMARY KEY,
    account_id       text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    scholar_id       text        NOT NULL REFERENCES core_scholar (id) ON DELETE RESTRICT,
    status           text        NOT NULL,
    statement        text        NOT NULL,
    submitted_at     timestamptz NOT NULL DEFAULT now(),
    decided_at       timestamptz,
    decided_by       text        REFERENCES iam_account (id) ON DELETE RESTRICT,
    decision_reason  text,
    idempotency_key  text,
    version          bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_wf_claim_request_status CHECK (
        status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE', 'APPROVED', 'REJECTED', 'DISPUTED')
    ),
    CONSTRAINT ck_wf_claim_request_decided CHECK (
        (status IN ('APPROVED', 'REJECTED', 'DISPUTED') AND decided_at IS NOT NULL)
        OR (status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE'))
    ),
    CONSTRAINT ck_wf_claim_request_statement CHECK (btrim(statement) <> '')
);
CREATE UNIQUE INDEX uk_wf_claim_request_idem
    ON wf_claim_request (account_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_wf_claim_request_scholar ON wf_claim_request (scholar_id, status);
CREATE INDEX ix_wf_claim_request_account ON wf_claim_request (account_id, submitted_at DESC);

CREATE TABLE wf_claim_evidence (
    id              text        PRIMARY KEY,
    claim_request_id text       NOT NULL REFERENCES wf_claim_request (id) ON DELETE RESTRICT,
    file_ref        text        NOT NULL,
    content_hash    text        NOT NULL,
    content_type    text        NOT NULL,
    size_bytes      bigint      NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_wf_claim_evidence_type CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    CONSTRAINT ck_wf_claim_evidence_size CHECK (size_bytes > 0 AND size_bytes <= 10485760)
);
COMMENT ON TABLE wf_claim_evidence IS
    '认领材料元数据与哈希。文件本体在受控卷，文件名使用随机内部 ID，不映射为公开目录（架构 §10.4）。';

CREATE TABLE wf_portal_change_request (
    id              text        PRIMARY KEY,
    account_id      text        NOT NULL REFERENCES iam_account (id) ON DELETE RESTRICT,
    scholar_id      text        NOT NULL REFERENCES core_scholar (id) ON DELETE RESTRICT,
    field_name      text        NOT NULL,
    current_value   text,
    proposed_value  text,
    requires_review boolean     NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING_REVIEW',
    submitted_at    timestamptz NOT NULL DEFAULT now(),
    decided_at      timestamptz,
    decided_by      text        REFERENCES iam_account (id) ON DELETE RESTRICT,
    decision_reason text,
    version         bigint      NOT NULL DEFAULT 1,
    CONSTRAINT ck_wf_portal_change_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_wf_portal_change_field CHECK (
        field_name IN ('RESEARCH_SUMMARY', 'ORGANIZATION_NAME', 'DISPLAY_ORDER', 'TOPIC_SET')
    )
);
COMMENT ON TABLE wf_portal_change_request IS
    '影响身份/归属或来源事实的字段须审批；待审版本绝不覆盖已公开版本（AC-IMPL-FR06-01）。';

-- -----------------------------------------------------------------------------
-- evt 事件域：Outbox 与投递状态、目录修订计数器
-- -----------------------------------------------------------------------------
CREATE TABLE evt_revision_counter (
    id               smallint    PRIMARY KEY,
    current_revision bigint      NOT NULL DEFAULT 0,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_evt_revision_counter_single CHECK (id = 1)
);
INSERT INTO evt_revision_counter (id, current_revision) VALUES (1, 0);

CREATE TABLE evt_outbox (
    id                 text        PRIMARY KEY,
    event_id           text        NOT NULL,
    event_type         text        NOT NULL,
    schema_version     integer     NOT NULL,
    aggregate_type     text        NOT NULL,
    aggregate_id       text        NOT NULL,
    aggregate_version  bigint      NOT NULL,
    catalog_revision   bigint      NOT NULL,
    occurred_at        timestamptz NOT NULL DEFAULT now(),
    trace_id           text,
    payload            jsonb       NOT NULL,
    CONSTRAINT uk_evt_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_evt_outbox_schema CHECK (schema_version >= 1)
);
COMMENT ON TABLE evt_outbox IS
    '变更集事件正文不可变；不含口令、材料与私人联系方式（架构 §6.3）。'
    '所有改变公开投影的核心事务都必须写 Outbox，不依赖内存监听器或 afterCommit 回调。';
CREATE INDEX ix_evt_outbox_revision ON evt_outbox (catalog_revision);

CREATE TABLE evt_outbox_delivery (
    id              text        PRIMARY KEY,
    outbox_id       text        NOT NULL REFERENCES evt_outbox (id) ON DELETE RESTRICT,
    consumer_id     text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    delivered_at    timestamptz,
    last_error      text,
    CONSTRAINT uk_evt_outbox_delivery UNIQUE (outbox_id, consumer_id),
    CONSTRAINT ck_evt_outbox_delivery_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);
COMMENT ON TABLE evt_outbox_delivery IS
    '按接收者保存投递状态。发送端收到 ACK 才标记已投递；ACK 丢失导致重复投递由接收端唯一键消除业务效果。';
CREATE INDEX ix_evt_outbox_delivery_pending
    ON evt_outbox_delivery (consumer_id, next_attempt_at) WHERE status = 'PENDING';

-- -----------------------------------------------------------------------------
-- aud 审计域：受保护写入的审计事件，只追加
-- -----------------------------------------------------------------------------
CREATE TABLE aud_audit_event (
    id               text        PRIMARY KEY,
    actor_account_id text,
    actor_username   text,
    action           text        NOT NULL,
    target_type      text        NOT NULL,
    target_id        text,
    before_value     jsonb,
    after_value      jsonb,
    reason           text,
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    trace_id         text
);
COMMENT ON TABLE aud_audit_event IS
    '审计与业务同事务提交；记录操作者、对象、时间、前后值与理由，不含材料原文（AC-IMPL-FR14-02）。只追加。';
CREATE INDEX ix_aud_audit_event_target ON aud_audit_event (target_type, target_id, occurred_at DESC);
CREATE INDEX ix_aud_audit_event_actor ON aud_audit_event (actor_account_id, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- 管理幂等：actor + operation + key
-- -----------------------------------------------------------------------------
CREATE TABLE evt_idempotency_record (
    id                text        PRIMARY KEY,
    actor_id          text        NOT NULL,
    operation         text        NOT NULL,
    idempotency_key   text        NOT NULL,
    request_hash      text        NOT NULL,
    result_reference  text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL,
    CONSTRAINT uk_evt_idempotency UNIQUE (actor_id, operation, idempotency_key)
);
COMMENT ON TABLE evt_idempotency_record IS
    '幂等记录与业务结果同事务提交，不只存 Redis；相同键同内容返回原结果，异内容 409（架构 §10.4）。';
