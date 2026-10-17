-- 候选的第三种终态：已改判（REBASED）。
--
-- 背景：成果合并之后，那两个标识各自还牵涉一批「对手方是第三方」的候选。这些候选问的问题
-- （「这两条是不是同一条」）因为端点已被并走而不再可答，但问题本身并没有消失——它变成了
-- 「最终实体与那个第三方是不是同一条」。原先系统只有两种处置：驳回，或先解析到最终实体后
-- 另建候选。前者会往台账里写一条「判定为不同实体」的不实话（两端的关系根本没被比较过），
-- 后者要求治理人员手工记住映射再重建候选，等价于让人肉去补一次数据迁移。
--
-- 因此补一种如实的状态：REBASED 表示「这条候选的问题不再由它作答，已转由 rebased_to_id
-- 指向的候选作答」。它与 REJECTED 的区别是结论的有无：REJECTED 是「比较过了，不是同一条」，
-- REBASED 是「还没比较，换了一条候选来问」。
--
-- 为什么不是把旧候选留在 OPEN：工作台按相似度降序，这批候选相似度通常是 1.0，永远排在最前面，
-- 而它们无论点合并还是点驳回都做不成——留下的结果是一条谁也处理不掉的死条目，把真正待判断的
-- 候选挤出首页。为什么不复用 REJECTED：驳回是可统计的治理结论，混入「其实没比较」的条目会让
-- 「驳回率」这类指标失真（AC-IMPL-FR09-02 要求裁决结论可复核）。

ALTER TABLE gov_dedup_candidate
    DROP CONSTRAINT ck_gov_dedup_candidate_status;

ALTER TABLE gov_dedup_candidate
    ADD CONSTRAINT ck_gov_dedup_candidate_status
    CHECK (status IN ('OPEN', 'MERGED', 'REJECTED', 'REBASED'));

-- 承接候选：改判后的那条候选。自引用外键，RESTRICT 与候选两端的外键口径一致——候选只裁决、
-- 不删除，因此不存在需要级联的场景；真删掉承接候选会让 REBASED 指向一个不存在的对象。
ALTER TABLE gov_dedup_candidate
    ADD COLUMN rebased_to_id text REFERENCES gov_dedup_candidate (id) ON DELETE RESTRICT;

-- 状态与指针必须同时成立：REBASED 一定要指向承接候选（否则「改判到哪去了」无从查证），
-- 其余状态一定不带指针（否则一条被驳回的候选会读起来像是还有下家）。
ALTER TABLE gov_dedup_candidate
    ADD CONSTRAINT ck_gov_dedup_candidate_rebased_ref
    CHECK ((status = 'REBASED') = (rebased_to_id IS NOT NULL));

COMMENT ON COLUMN gov_dedup_candidate.rebased_to_id IS
    '改判后承接本条候选问题的候选标识；仅当 status = REBASED 时非空。';
