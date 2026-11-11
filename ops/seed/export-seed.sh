#!/usr/bin/env bash
# 导出离线种子数据包（部署前功能项一）。
#
# 解决的问题：部署环境没有外网时，新库是空的，采集任务跑不起来，平台打开就是零结果。
# 种子包把当前库里**真实公开**的目录数据固化下来，新环境导入后立刻有可检索、可排榜、
# 可画引用网络、可算推荐的内容。
#
# 为什么用 CSV 而不是 pg_dump -Fc：
#   -Fc 是「整库快照」，绑死当时的 schema 版本，且无法只取一个子集；种子要的是**可审计的
#   子集**（多少条成果、多少条关系，写在 manifest 里），还要能逐表核对行数。
#   CSV + manifest 能做到「导入后逐表比对」，-Fc 只能整库比。
#
# 为什么导出要在一个 REPEATABLE READ 事务里做完：
#   采集任务随时在写。分成多条 psql 调用会得到「成果是 10:00 的、参与关系是 10:03 的」这种
#   自相矛盾的包——某条关系指向的成果不在包里，导入时外键直接失败。所有 \copy 共用一个事务，
#   读的就是同一个快照。
#
# 数据红线（导出侧强制）：
#   1. 只导 data_nature = REAL 的行。库里现有 2 条 SIMULATED，必须挡在外面——种子包一旦带上
#      模拟数据，新环境就无法再声称「库里都是真实来源」。
#   2. 只导 visibility = PUBLIC 且 effective_status = EFFECTIVE 的成果。RESTRICTED、
#      WITHDRAWN、PENDING_GOVERNANCE 一律不导：种子包会被分发到多个环境，受限数据一旦出去
#      就收不回来。
#   3. 不导任何账号、会话、审计、通知、工作流（认领/变更/提交）、治理台账。种子只有目录内容，
#      没有任何人的身份信息。
#
# 用法：
#   bash ops/seed/export-seed.sh                  # 默认 1500 条高被引论文
#   bash ops/seed/export-seed.sh --limit 3000     # 换规模
#   bash ops/seed/export-seed.sh --no-payload     # 不带来源报文原文（体积可降一个量级）
#   bash ops/seed/export-seed.sh --out /data/seed
#
# 退出码：0 成功；2 前置条件不满足（连不上库 / 没有 pg 工具）；3 导出失败；4 导出结果为空。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

PG_BIN="${PG_BIN:-/d/dev-tools/pgsql/bin}"
SRC_HOST="${SRC_HOST:-127.0.0.1}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_DB="${SRC_DB:-core_db}"
SRC_USER="${SRC_USER:-core_owner}"
SRC_PASSWORD="${SRC_PASSWORD:-cn_core_owner_dev}"

LIMIT=1500
WITH_PAYLOAD=1
OUT_DIR="ops/seed/dist"

while [ $# -gt 0 ]; do
  case "$1" in
    --limit)
      LIMIT="$2"
      shift 2
      ;;
    --no-payload)
      WITH_PAYLOAD=0
      shift
      ;;
    --out)
      OUT_DIR="$2"
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

case "$LIMIT" in
  '' | *[!0-9]*)
    echo "--limit 必须是正整数" >&2
    exit 2
    ;;
esac
[ "$LIMIT" -gt 0 ] || {
  echo "--limit 必须大于 0" >&2
  exit 2
}

PSQL="$PG_BIN/psql.exe"
[ -x "$PSQL" ] || PSQL="$PG_BIN/psql"
if [ ! -x "$PSQL" ]; then
  echo "找不到 psql（PG_BIN=$PG_BIN）" >&2
  exit 2
fi

run_psql() {
  PGPASSWORD="$SRC_PASSWORD" PGCLIENTENCODING=UTF8 "$PSQL" \
    -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" -d "$SRC_DB" -v ON_ERROR_STOP=1 "$@"
}

if ! run_psql -Atc "SELECT 1" >/dev/null 2>&1; then
  echo "连不上 $SRC_USER@$SRC_HOST:$SRC_PORT/$SRC_DB" >&2
  exit 2
fi

mkdir -p "$OUT_DIR" || exit 3
# psql 是原生 Windows 程序，不接受 /d/... 这种 MSYS 路径，统一转成 D:/...
OUT_ABS="$(cygpath -m "$(cd "$OUT_DIR" && pwd)")"
# SQL 脚本也放 ASCII 路径：Windows 版 psql 用本地编码解析文件路径，用户目录名含中文时
# 临时目录会解析失败。
mkdir -p target
SQL_FILE="$(mktemp "target/cn-seed-export.XXXXXX.sql")"
trap 'rm -f "$SQL_FILE"' EXIT

# 表 → 列。顺序即 COPY 顺序，导入时原样使用（manifest 里也记一份，避免两处漂移）。
# 依赖顺序：被引用表在前。
TABLES=""
add_table() {
  TABLES="$(printf '%s\n%s|%s' "$TABLES" "$1" "$2")"
}

add_table ref_topic \
  "id,canonical_name,name_norm,source_category,created_at,updated_at"
add_table ref_organization \
  "id,canonical_name,name_norm,country_code,created_at,updated_at"
add_table core_scholar \
  "id,display_name,name_norm,data_nature,claimed_status,organization_name,research_summary,merged_into_id,version,created_at,updated_at,homepage_url,avatar_ref,avatar_media_type,profile_updated_at"
add_table core_achievement \
  "id,type,title,title_norm,data_nature,visibility,effective_status,publication_year,year_precision,merged_into_id,version,created_at,updated_at,display_restriction_source_id"
add_table core_paper \
  "achievement_id,doi,doi_norm,venue,published_date,preprint_status,citation_count,citation_source,abstract_text"
add_table raw_source_config \
  "id,source_code,display_name,enabled,license_scope,base_url,config_version,allowed_fields,created_at,updated_at,display_license_status,display_license_changed_at,display_license_note"
add_table raw_source_record \
  "id,source_id,source_record_id,content_fingerprint,payload,data_nature,source_updated_at,source_published_at,fetched_at,received_at,catalog_revision,first_received_at"
add_table core_contribution \
  "id,scholar_id,achievement_id,role,author_order,relation_state,source_record_id,created_at,updated_at,version"
add_table core_contribution_org \
  "contribution_id,organization_id"
add_table core_achievement_source \
  "id,achievement_id,source_record_id,source_id,source_updated_at,received_at,created_at"
add_table core_identifier_registry \
  "id,entity_type,entity_id,identifier_kind,scope,normalized_value,created_at"
add_table core_achievement_topic \
  "achievement_id,topic_id,source_category,assignment_kind,confidence,rule_version,evidence"
add_table core_topic_inference \
  "achievement_id,rule_version,outcome,assigned_count,best_confidence,evidence,inferred_at"
add_table core_field_provenance \
  "id,entity_type,entity_id,field_name,value_level,value_text,source_record_id,updated_at"
add_table core_scholar_name_variant \
  "id,scholar_id,variant,variant_norm,evidence,created_at"
add_table core_scholar_featured \
  "scholar_id,achievement_id,rank,created_at"
add_table core_citation_observation \
  "achievement_id,provider,provider_work_id,references_total,references_listed,outcome,fetched_at,created_at"
add_table core_citation_reference \
  "id,citing_achievement_id,cited_kind,cited_value_norm,cited_achievement_id,resolution,observed_at,resolved_at"

if [ "$WITH_PAYLOAD" -eq 0 ]; then
  # 不带原文时用显式标记，不用空对象：空对象会被下游当成「来源确实返回了空报文」，
  # 而这个标记能让人一眼看出是种子包裁掉的。
  PROV_PAYLOAD="'{\"_seed_omitted\": true}'::jsonb"
else
  PROV_PAYLOAD="payload"
fi

# 每张表的选区。seed_* 临时表定义一次，后面全部复用，保证 18 个文件是同一份选区。
{
  cat <<SQL
\set ON_ERROR_STOP on
-- 不能加 READ ONLY：REPEATABLE READ 下建临时表会被拒（CREATE TABLE AS 仍算写），
-- 而临时表正是「一次选区、18 个文件共用」的实现方式。临时表只在会话内，不落库。
BEGIN ISOLATION LEVEL REPEATABLE READ;

-- 基准成果集：公开、生效、未合并、真实数据，按被引与年份取头部。
-- 先按 is not null 排空值，再按 id 兜底，保证同一库重复导出的顺序稳定。
--
-- 额外排除「带 e2e 自检来源归属」的成果：库里 3 个 source_code 以 e2e- 开头的来源是端到端
-- 自检时临时建的，内容虽取自 OpenAlex，但来源配置名叫「端到端自检来源」。种子包要分发到
-- 新环境，带上这种东西会让人误以为平台自己在造数据。库里这类成果 8988 条，剔除后仍有
-- 8359 条干净成果，够用。
CREATE TEMP TABLE seed_ach AS
SELECT a.id
  FROM core_achievement a
  JOIN core_paper p ON p.achievement_id = a.id
 WHERE a.visibility = 'PUBLIC'
   AND a.effective_status = 'EFFECTIVE'
   AND a.merged_into_id IS NULL
   AND a.data_nature = 'REAL'
   AND NOT EXISTS (
         SELECT 1
           FROM core_achievement_source s
           JOIN raw_source_config c ON c.id = s.source_id
          WHERE s.achievement_id = a.id
            AND c.source_code LIKE 'e2e-%')
 ORDER BY p.citation_count DESC NULLS LAST, a.publication_year DESC NULLS LAST, a.id
 LIMIT $LIMIT;

-- 参与只导非空来源配置：上面已保证种子成果不挂 e2e 来源，这里是第二道闸。
CREATE TEMP TABLE seed_src_cfg AS
SELECT DISTINCT s.source_id
  FROM core_achievement_source s
  JOIN raw_source_config c ON c.id = s.source_id
 WHERE s.achievement_id IN (SELECT id FROM seed_ach)
   AND c.source_code NOT LIKE 'e2e-%';

CREATE TEMP TABLE seed_sch AS
SELECT DISTINCT c.scholar_id
  FROM core_contribution c
  JOIN core_scholar s ON s.id = c.scholar_id
 WHERE c.achievement_id IN (SELECT id FROM seed_ach)
   AND s.data_nature = 'REAL';

CREATE TEMP TABLE seed_contrib AS
SELECT c.id
  FROM core_contribution c
 WHERE c.achievement_id IN (SELECT id FROM seed_ach)
   AND c.scholar_id IN (SELECT scholar_id FROM seed_sch);

CREATE TEMP TABLE seed_org AS
SELECT DISTINCT co.organization_id
  FROM core_contribution_org co
 WHERE co.contribution_id IN (SELECT id FROM seed_contrib);

CREATE TEMP TABLE seed_prov AS
SELECT fp.*
  FROM core_field_provenance fp
 WHERE (fp.entity_type = 'ACHIEVEMENT' AND fp.entity_id IN (SELECT id FROM seed_ach))
    OR (fp.entity_type = 'SCHOLAR' AND fp.entity_id IN (SELECT scholar_id FROM seed_sch));

CREATE TEMP TABLE seed_src AS
SELECT s.source_record_id FROM core_achievement_source s
 WHERE s.achievement_id IN (SELECT id FROM seed_ach)
UNION
-- 参与关系自己也带 source_record_id（外键到 raw_source_record）。不并入就会漏掉这些记录，
-- 导入时 contribution 的外键直接失败——这是实测过的坑，不是假设。
SELECT c.source_record_id FROM core_contribution c
 WHERE c.id IN (SELECT id FROM seed_contrib) AND c.source_record_id IS NOT NULL
UNION
SELECT p.source_record_id FROM seed_prov p WHERE p.source_record_id IS NOT NULL;
SQL

  # 逐表 \copy。日期/时间戳按 csv 输出，导入时由 PostgreSQL 按列类型解析。
  while IFS='|' read -r table columns; do
    [ -n "$table" ] || continue
    case "$table" in
      ref_topic)
        select_sql="SELECT id,canonical_name,name_norm,source_category,created_at,updated_at FROM ref_topic ORDER BY id"
        ;;
      ref_organization)
        select_sql="SELECT id,canonical_name,name_norm,country_code,created_at,updated_at FROM ref_organization WHERE id IN (SELECT organization_id FROM seed_org) ORDER BY id"
        ;;
      core_scholar)
        select_sql="SELECT id,display_name,name_norm,data_nature,claimed_status,organization_name,research_summary,merged_into_id,version,created_at,updated_at,homepage_url,avatar_ref,avatar_media_type,profile_updated_at FROM core_scholar WHERE id IN (SELECT scholar_id FROM seed_sch) ORDER BY id"
        ;;
      core_achievement)
        select_sql="SELECT id,type,title,title_norm,data_nature,visibility,effective_status,publication_year,year_precision,merged_into_id,version,created_at,updated_at,display_restriction_source_id FROM core_achievement WHERE id IN (SELECT id FROM seed_ach) ORDER BY id"
        ;;
      core_paper)
        select_sql="SELECT achievement_id,doi,doi_norm,venue,published_date,preprint_status,citation_count,citation_source,abstract_text FROM core_paper WHERE achievement_id IN (SELECT id FROM seed_ach) ORDER BY achievement_id"
        ;;
      raw_source_config)
        select_sql="SELECT id,source_code,display_name,enabled,license_scope,base_url,config_version,allowed_fields,created_at,updated_at,display_license_status,display_license_changed_at,display_license_note FROM raw_source_config WHERE id IN (SELECT source_id FROM seed_src_cfg) ORDER BY id"
        ;;
      raw_source_record)
        select_sql="SELECT id,source_id,source_record_id,content_fingerprint,$PROV_PAYLOAD,data_nature,source_updated_at,source_published_at,fetched_at,received_at,catalog_revision,first_received_at FROM raw_source_record WHERE id IN (SELECT source_record_id FROM seed_src) AND source_id IN (SELECT source_id FROM seed_src_cfg) ORDER BY id"
        ;;
      core_contribution)
        select_sql="SELECT id,scholar_id,achievement_id,role,author_order,relation_state,source_record_id,created_at,updated_at,version FROM core_contribution WHERE id IN (SELECT id FROM seed_contrib) ORDER BY id"
        ;;
      core_contribution_org)
        select_sql="SELECT contribution_id,organization_id FROM core_contribution_org WHERE contribution_id IN (SELECT id FROM seed_contrib) ORDER BY contribution_id,organization_id"
        ;;
      core_achievement_source)
        select_sql="SELECT id,achievement_id,source_record_id,source_id,source_updated_at,received_at,created_at FROM core_achievement_source WHERE achievement_id IN (SELECT id FROM seed_ach) AND source_id IN (SELECT source_id FROM seed_src_cfg) ORDER BY id"
        ;;
      core_identifier_registry)
        select_sql="SELECT id,entity_type,entity_id,identifier_kind,scope,normalized_value,created_at FROM core_identifier_registry WHERE (entity_type = 'ACHIEVEMENT' AND entity_id IN (SELECT id FROM seed_ach)) OR (entity_type = 'SCHOLAR' AND entity_id IN (SELECT scholar_id FROM seed_sch)) ORDER BY id"
        ;;
      core_achievement_topic)
        select_sql="SELECT achievement_id,topic_id,source_category,assignment_kind,confidence,rule_version,evidence FROM core_achievement_topic WHERE achievement_id IN (SELECT id FROM seed_ach) ORDER BY achievement_id,topic_id"
        ;;
      core_topic_inference)
        select_sql="SELECT achievement_id,rule_version,outcome,assigned_count,best_confidence,evidence,inferred_at FROM core_topic_inference WHERE achievement_id IN (SELECT id FROM seed_ach) ORDER BY achievement_id"
        ;;
      core_field_provenance)
        select_sql="SELECT id,entity_type,entity_id,field_name,value_level,value_text,source_record_id,updated_at FROM core_field_provenance WHERE id IN (SELECT id FROM seed_prov) ORDER BY id"
        ;;
      core_scholar_name_variant)
        select_sql="SELECT id,scholar_id,variant,variant_norm,evidence,created_at FROM core_scholar_name_variant WHERE scholar_id IN (SELECT scholar_id FROM seed_sch) ORDER BY id"
        ;;
      core_scholar_featured)
        select_sql="SELECT scholar_id,achievement_id,rank,created_at FROM core_scholar_featured WHERE scholar_id IN (SELECT scholar_id FROM seed_sch) AND achievement_id IN (SELECT id FROM seed_ach) ORDER BY scholar_id,rank"
        ;;
      core_citation_observation)
        select_sql="SELECT achievement_id,provider,provider_work_id,references_total,references_listed,outcome,fetched_at,created_at FROM core_citation_observation WHERE achievement_id IN (SELECT id FROM seed_ach) ORDER BY achievement_id,provider"
        ;;
      core_citation_reference)
        # cited_achievement_id 指向包外成果时外键会失败；只保留「指向包内」与「指为 EXTERNAL」两种。
        select_sql="SELECT id,citing_achievement_id,cited_kind,cited_value_norm,cited_achievement_id,resolution,observed_at,resolved_at FROM core_citation_reference WHERE citing_achievement_id IN (SELECT id FROM seed_ach) AND (cited_achievement_id IS NULL OR cited_achievement_id IN (SELECT id FROM seed_ach)) ORDER BY id"
        ;;
      *)
        echo "内部错误：表 $table 没有对应选区" >&2
        exit 3
        ;;
    esac
    printf '\\copy (%s) TO %s WITH (FORMAT csv, HEADER true, FORCE_QUOTE *)\n' \
      "$select_sql" "'$OUT_ABS/$table.csv'"
    # 行数从数据库数，不从 CSV 数：字段里可以含换行（摘要、溯源值都会），
    # `wc -l` 会把一条记录算成两行，于是 manifest 与导入后的 count(*) 差 1，
    # 而数据其实一条没少。这是实测踩到的假失败。
    printf "SELECT 'CNSEED_ROWS:%s:' || count(*) FROM (%s) AS cnt;\n" "$table" "$select_sql"
  done <<<"$TABLES"

  # 水位表：重建投影要用它当基线，必须跟着种子一起走，否则新库 revision 从 0 起、
  # query 侧已发布水位与 core 不一致。
  printf "\\\\copy (SELECT id,current_revision,updated_at,required_public_revision FROM evt_revision_counter) TO '%s/evt_revision_counter.csv' WITH (FORMAT csv, HEADER true, FORCE_QUOTE *)\n" "$OUT_ABS"
  printf "SELECT 'CNSEED_ROWS:evt_revision_counter:' || count(*) FROM evt_revision_counter;\n"

  echo "COMMIT;"
} >"$SQL_FILE"

echo "导出到 $OUT_ABS（limit=$LIMIT, payload=$WITH_PAYLOAD）"
PSQL_OUT="$(run_psql -f "$SQL_FILE" 2>&1)" || {
  printf '%s\n' "$PSQL_OUT" >&2
  echo "导出失败" >&2
  exit 3
}

# 取某张表的真实行数：导出会话里紧跟着 \copy 打了一行 CNSEED_ROWS:<表>:<n>。
# 不用 CSV 行数是因为字段可以含换行，行数与记录数不是一回事。
# psql 的表格输出会按列宽补空格（「 CNSEED_ROWS:t:18   」），所以这里按行首尾空白裁剪后匹配，
# 不能直接把整行当字段比较。
rows_of() {
  printf '%s\n' "$PSQL_OUT" |
    sed -n "s/^[[:space:]]*CNSEED_ROWS:$1:\([0-9][0-9]*\)[[:space:]]*$/\1/p" |
    head -1
}

# 压缩 + 写 manifest。manifest 是纯 key=value，导入脚本只用 awk 读，不引入 JSON 解析依赖。
MANIFEST="$OUT_DIR/manifest.properties"
FLYWAY_VERSION="$(run_psql -Atc "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
EXPORTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  echo "format=cn-seed/1"
  echo "flywayVersion=$FLYWAY_VERSION"
  echo "exportedAt=$EXPORTED_AT"
  echo "sourceDatabase=$SRC_DB"
  echo "limit=$LIMIT"
  echo "payloadIncluded=$WITH_PAYLOAD"
  echo "note=只含 PUBLIC/EFFECTIVE/REAL 的目录数据；不含账号、会话、审计、通知、工作流与治理台账"
} >"$MANIFEST"

TOTAL_ROWS=0
while IFS='|' read -r table columns; do
  [ -n "$table" ] || continue
  csv="$OUT_DIR/$table.csv"
  if [ ! -f "$csv" ]; then
    echo "缺失导出文件：$csv" >&2
    exit 3
  fi
  rows="$(rows_of "$table")"
  case "$rows" in
    ''|*[!0-9]*)
      echo "取不到 $table 的行数（导出会话没有回报 CNSEED_ROWS:$table）" >&2
      exit 3
      ;;
  esac
  gzip -9 -f "$csv"
  sum=$(sha256sum "$OUT_DIR/$table.csv.gz" | cut -d' ' -f1)
  {
    echo "table.$table.file=$table.csv.gz"
    echo "table.$table.rows=$rows"
    echo "table.$table.sha256=$sum"
    echo "table.$table.columns=$columns"
  } >>"$MANIFEST"
  TOTAL_ROWS=$((TOTAL_ROWS + rows))
  printf '  %-32s %8s 行\n' "$table" "$rows"
done <<<"$TABLES"

# 水位表也进 manifest（不参与「表必须为空」检查，导入时单独 upsert）。
csv="$OUT_DIR/evt_revision_counter.csv"
rev_rows="$(rows_of evt_revision_counter)"
case "$rev_rows" in
  ''|*[!0-9]*)
    echo "取不到 evt_revision_counter 的行数" >&2
    exit 3
    ;;
esac
gzip -9 -f "$csv"
sum=$(sha256sum "$OUT_DIR/evt_revision_counter.csv.gz" | cut -d' ' -f1)
{
  echo "table.evt_revision_counter.file=evt_revision_counter.csv.gz"
  echo "table.evt_revision_counter.rows=$rev_rows"
  echo "table.evt_revision_counter.sha256=$sum"
  echo "table.evt_revision_counter.columns=id,current_revision,updated_at,required_public_revision"
} >>"$MANIFEST"

echo "总行数 $TOTAL_ROWS"
echo "manifest: $MANIFEST"

# 导出结果为空说明选区条件写错了或库是空的，此时「导入成功」只会得到一个空站点。
if [ "$TOTAL_ROWS" -eq 0 ]; then
  echo "导出结果为空，拒绝产出种子包" >&2
  exit 4
fi

du -sh "$OUT_DIR" 2>/dev/null || true
exit 0
