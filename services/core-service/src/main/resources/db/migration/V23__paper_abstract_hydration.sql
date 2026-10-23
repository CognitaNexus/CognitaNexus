-- 摘要补水：记录「已向外部来源尝试取过摘要」的时刻，把取不到的排除出待补清单。
--
-- 依据：FR02／FR16（成果元数据来源可追溯）、NF01（检索可用性）。
--
-- 为什么需要这一列（2026-10-22 线上实测）：
--   18,000 篇公开生效成果里 **7,251 篇没有摘要**（40%）。检索的关键词条件有五支，摘要占其中一支，
--   所以缺摘要的成果只能靠题名命中——用户搜方法与结论里的词，这些成果一律搜不到。
--   随机抽 20 篇缺摘要的论文去 OpenAlex 批量查，**9 篇有摘要（45%）**；Elsevier 系（10.1016）
--   普遍不提供。按 45% 估算，可补齐约 3,200 篇，摘要覆盖率从 60% 提到 78%。
--
-- 为什么不靠「每轮重试」而要把尝试记下来：
--   取不到的占 55%。没有标记时，每一轮都会把同一批永远取不到的 DOI 再问一遍；等可补的补完之后，
--   系统会永久性地每轮空转几百个 DOI，而日志上看起来一切正常——正是那种不会被发现的腐坏。
--
-- 为什么用可空时间戳而不是 state 枚举：
--   状态可以完全由已有字段推出来——abstract_text 非空即已补齐；为空且 abstract_checked_at 非空
--   即「取过，来源没有」；两者都空即待补。多一个枚举列就多一个可能与事实矛盾的字段，而这类矛盾
--   不会报错，只会让待补清单悄悄算错。
--
-- 为什么可以放心加可空列：
--   不改变任何已有行的语义（全部为 NULL ＝ 从未尝试），旧代码不认识这一列也不受影响。
--
-- 为什么写 IF NOT EXISTS：
--   这一列允许先手工加上去验证查询，再由 Flyway 正式登记（与 V5／V6 的索引用同一口径）。
--   不加这个限定的话，手工预演过一次的库会在启动时因「column already exists」直接起不来。
ALTER TABLE core_paper ADD COLUMN IF NOT EXISTS abstract_checked_at timestamptz;

COMMENT ON COLUMN core_paper.abstract_checked_at IS
    '最近一次向外部来源尝试取摘要的时刻；NULL 表示从未尝试。取到与取不到都写，用来把「取不到」排除出待补清单';

-- 待补清单的入口索引：只索引从未尝试过的行，随补齐进行会越来越小。
CREATE INDEX ix_core_paper_abstract_pending ON core_paper (achievement_id)
    WHERE abstract_checked_at IS NULL;

COMMENT ON INDEX ix_core_paper_abstract_pending IS
    '待补摘要清单：部分索引，只覆盖 abstract_checked_at IS NULL 的行';
