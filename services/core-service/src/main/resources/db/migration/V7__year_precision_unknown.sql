-- 允许「年份缺失」以两种等价表示之一出现
--
-- 背景：V1 的 ck_core_achievement_year_precision 只承认 (publication_year IS NULL
-- AND year_precision IS NULL)。但领域模型 PublicationYear 明确区分两种「没有年份」：
--   (year = null, precision = null)      —— 连「为什么会缺」都还没判定
--   (year = null, precision = 'UNKNOWN') —— 已判定：来源未提供年份
-- 后者的存在意义就是「保持未知并说明原因」（架构 §5.4），把它压成 NULL 会丢掉这个判定。
--
-- 该冲突不是理论问题：Crossref 中有相当比例的条目没有 issued 日期，接收首批真实数据时
-- 直接触发约束违例，core 返回 500，整批交接失败——一个正常数据形态把整条链路打断。
--
-- 放宽后的判据仍然禁止真正的错误形态：年份存在时精度必须是已知取值，年份不存在时
-- 精度不得是 YEAR。也就是说，「有年份但精度是 YEAR 以外」与「没年份却说年份可信」
-- 仍然写不进去。
--
-- 另注：年份仍以年份值存放，绝不补 1 月 1 日；本迁移只影响精度列的取值域。

ALTER TABLE core_achievement
    DROP CONSTRAINT ck_core_achievement_year_precision;

ALTER TABLE core_achievement
    ADD CONSTRAINT ck_core_achievement_year_precision CHECK (
        (publication_year IS NULL AND (year_precision IS NULL OR year_precision = 'UNKNOWN'))
        OR (publication_year IS NOT NULL AND year_precision IN ('YEAR', 'UNKNOWN'))
    );

COMMENT ON COLUMN core_achievement.year_precision IS
    '年份精度标记。YEAR=来源给出的年份可用；UNKNOWN=已判定来源未提供年份。'
    '年份缺失时精度列可为 NULL（尚未判定）或 UNKNOWN（已判定未提供），两者都表示年份未知，'
    '不表示任何可用于统计的年份值。';
