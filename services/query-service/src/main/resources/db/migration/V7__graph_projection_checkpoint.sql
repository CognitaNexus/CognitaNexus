-- =============================================================================
-- CognitaNexus query-service 迁移 V7：Neo4j 图投影的 Backfill 断点与运行状态
-- 依据：ADR-005 §5.2（分批 Backfill 与断点）、§8.2（全量重建与对账）
--
-- 为什么断点必须落库而不是留在内存：
--   图投影的回填是几十万到几百万条成果的长任务，进程重启、发布、OOM、图库重启都会打断它。
--   内存 offset 无法解释「哪些成果已经真正写进图里」——重启后从 0 重扫看似安全（写是幂等的），
--   但代价是每次中断都要重扫全量，重建窗口被无限拉长；而从「上次看到的那一条」继续，
--   必须有一个持久化的游标。游标用 work_id（成果标识）而不是数字下标：分页按 work_id 稳定
--   排序，插入新成果不会让游标错位，重复扫描的批次因幂等而安全。
--
-- 为什么单独一张表而不是塞进 GraphProjectionMeta（Neo4j）：
--   水位（projected_version）是读路径判断「能不能用图」的依据，必须极简且只前进；
--   断点还包含批次、失败计数、错误原因、状态机，属于运维可观测数据。两者混在一起会让
--   一次写断点失败变成「水位回退」，而水位回退会让已下线的边重新出现。
--
-- 本迁移只新增一张表，不修改、不删除任何既有表与数据（AGENTS.md §2.3）。
-- =============================================================================

CREATE TABLE qry_graph_projection_checkpoint (
    scope             text        PRIMARY KEY,
    status            text        NOT NULL,
    generation        text        NOT NULL,
    cursor_work_id    text,
    base_revision     bigint      NOT NULL DEFAULT 0,
    processed_works   bigint      NOT NULL DEFAULT 0,
    processed_batches integer     NOT NULL DEFAULT 0,
    failed_works      bigint      NOT NULL DEFAULT 0,
    last_error        text,
    started_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    CONSTRAINT ck_qry_graph_ckpt_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_qry_graph_ckpt_batches CHECK (processed_batches >= 0),
    CONSTRAINT ck_qry_graph_ckpt_works CHECK (processed_works >= 0),
    CONSTRAINT ck_qry_graph_ckpt_failed CHECK (failed_works >= 0)
);

COMMENT ON TABLE qry_graph_projection_checkpoint IS
    'Neo4j 图投影 Backfill 的持久化断点（每个 scope 一行）。RUNNING 表示可从中断处继续；'
    'COMPLETED 表示该代已全量灌完，之后由事件增量追平；FAILED 保留 cursor 与错误原因供定位，'
    '修复后可以 resume 而不用从头重扫。scope 唯一保证同一实例上不会出现两套互不相同的进度。';
COMMENT ON COLUMN qry_graph_projection_checkpoint.cursor_work_id IS
    '最后一条已处理的成果标识（按 work_id 稳定升序分页）。NULL 表示尚未处理任何成果：'
    '这与「已处理 0 条」不同，用于区分首次启动与空代。';
COMMENT ON COLUMN qry_graph_projection_checkpoint.base_revision IS
    '本次回填开始时的 PG 已发布修订（qry_consumer_state.published_revision）。回填完成后图水位'
    '推进到该值，之后的事件由增量消费者从该修订之后追平——「快照 + 回放」的锚点。';
COMMENT ON COLUMN qry_graph_projection_checkpoint.processed_works IS
    '已成功写入图（APPLIED/RETIRED/STALE 均计入）的成果数，用于和 qry_achievement 行数对账；'
    '失败数单独记在 failed_works，不混入这个数字，否则「看起来全处理完了」会掩盖失败。';
COMMENT ON COLUMN qry_graph_projection_checkpoint.failed_works IS
    '本代中因数据/图库错误未能写入的成果数。超过 cn.query.graph.projection.max-failed-works 时'
    '整个运行转 FAILED，避免「跑完了但有几万条没进图」被当成成功。';
