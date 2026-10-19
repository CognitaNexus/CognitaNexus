-- 学者门户自助维护：主页链接、头像、代表作置顶。
--
-- 依据：SRS 5.4（学者档案）、AC-IMPL-FR06-01（门户信息变更）、待办「学者主页不能自己维护」。
--
-- 划分原则（这次新增的三类都属于「本人自述」，不改变来源事实）：
--   * research_summary / homepage_url / avatar：认领人自己写自己，直接生效，公开页标注「本人填写」；
--   * display_name / organization_name：身份与归属，仍走审批（portal-changes），待审版本不覆盖已公开版本。
--
-- 头像只存「引用 + 媒体类型」，不把二进制塞进 core_scholar：档案行会被频繁读取，
-- 把图片放进主表会让每次读档案都拖着一份图片数据。

ALTER TABLE core_scholar ADD COLUMN IF NOT EXISTS homepage_url text;
ALTER TABLE core_scholar ADD COLUMN IF NOT EXISTS avatar_ref text;
ALTER TABLE core_scholar ADD COLUMN IF NOT EXISTS avatar_media_type text;
ALTER TABLE core_scholar ADD COLUMN IF NOT EXISTS profile_updated_at timestamptz;

COMMENT ON COLUMN core_scholar.homepage_url IS
    '本人填写的主页地址（http/https）；只作为外链展示，不抓取内容';
COMMENT ON COLUMN core_scholar.avatar_ref IS
    '头像文件在受控卷内的相对引用；为空表示未上传，公开页退回姓名首字占位';
COMMENT ON COLUMN core_scholar.profile_updated_at IS
    '自助资料最后一次修改时间；用于公开页标注「资料由本人维护于 …」';

-- 代表作置顶：每位学者最多 3 条，顺序由 rank 决定。
--
-- 为什么单独一张表而不是在 core_scholar 上放一个 id 数组：置顶必须能校验「这条成果确实属于该学者
-- 且当前公开」，数组列在数据库里无法参与外键与唯一性约束，删除成果时会留下悬空引用。
CREATE TABLE IF NOT EXISTS core_scholar_featured (
    scholar_id     text NOT NULL REFERENCES core_scholar (id) ON DELETE CASCADE,
    achievement_id text NOT NULL REFERENCES core_achievement (id) ON DELETE CASCADE,
    rank           integer NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scholar_id, achievement_id),
    CONSTRAINT core_scholar_featured_rank_range CHECK (rank BETWEEN 1 AND 3)
);

-- 同一学者的名次不可重复：两条并列第 1 会让「置顶顺序」在读取时变成不确定。
CREATE UNIQUE INDEX IF NOT EXISTS ux_core_scholar_featured_rank
    ON core_scholar_featured (scholar_id, rank);

COMMENT ON TABLE core_scholar_featured IS
    '门户代表作置顶，最多 3 条；写入前由服务端校验成果属于该学者且公开可读';

-- 运行期角色无需在此单独授权：V6 已为本迁移账号设置 ALTER DEFAULT PRIVILEGES，
-- 之后新建的表自动带上 SELECT/INSERT/UPDATE/DELETE。此处不再写 GRANT，
-- 避免在「角色不存在」的环境里把一次结构迁移变成启动失败。
