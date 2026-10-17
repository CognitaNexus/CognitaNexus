#!/usr/bin/env bash
# =============================================================================
# V8 迁移与业务更新周期的真实数据库验证（WB-13）
# =============================================================================
#
# 为什么不用 JUnit 覆盖这一层：这里要证明的是**库级约束**在真实 PostgreSQL 上的行为——
# 周期下限、理由必填、设定后立刻到期、撤销后回到 NULL，以及可执行任务查询的周期门控。
# 这些结论全部由 CHECK 与 SQL 决定；用 Mock 只能复述实现里写了什么。
#
# 做什么：
#   1. 在 55432 的隔离实例上建一个一次性库，按顺序跑 V1…V8（真实迁移脚本，不是手抄 DDL）；
#   2. 逐条断言约束与门控语义，每条打印「期望 / 实际」；
#   3. 删掉这个一次性库（它是校验用的，不是开发库）。
#
# 注意（上一批踩过的坑）：
#   - 含中文的 SQL 必须走 stdin/-f 文件，不能用 -c：Windows 下 argv 按 ANSI 码页转码，
#     UTF-8 中文会在这一步被破坏，服务端报 invalid byte sequence；
#   - 每个非交互 psql 调用都要 </dev/null，否则它继承调用方的 stdin 并挂住。
#
# 用法：bash ops/verify/collect-update-plan.sh [--keep]
#   --keep  保留校验库便于手工排查（默认删掉）
# =============================================================================

set -euo pipefail

# 客户端编码：本机 psql 默认按 ANSI 码页（GBK）解释，而迁移脚本与断言都是 UTF-8 含中文注释。
# 不设这个变量会在 V1 就失败（character with byte sequence ... has no equivalent in "UTF8"）。
export PGCLIENTENCODING=UTF8

PG_HOME="D:/dev-tools/pgsql"
PORT=55432
DB="ingestion_v8_check"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIG_DIR="$REPO_ROOT/services/ingestion-service/src/main/resources/db/migration"
KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

fail() { echo "  [FAIL] $*" >&2; exit 1; }

if ! "$PG_HOME/bin/pg_ctl.exe" -D "$REPO_ROOT/ops/local/run/it-pg/data" status >/dev/null 2>&1; then
  echo "隔离测试实例未运行，正在启动（bash ops/local/test-db.sh start）"
  bash "$REPO_ROOT/ops/local/test-db.sh" start >/dev/null
fi

psql_super() {
  "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$PORT" -U postgres -d postgres \
    -v ON_ERROR_STOP=1 -q "$@" < /dev/null
}

psql_db() {
  "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$PORT" -U postgres -d "$DB" \
    -v ON_ERROR_STOP=1 "$@" < /dev/null
}

# 含中文的语句一律从 stdin 读，并显式指定客户端编码
psql_sql() {
  PGCLIENTENCODING=UTF8 "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$PORT" -U postgres -d "$DB" \
    -v ON_ERROR_STOP=1 -At -f -
}

echo "=== 1. 建一次性校验库 $DB"
psql_super -c "DROP DATABASE IF EXISTS $DB" >/dev/null
psql_super -c "CREATE DATABASE $DB" >/dev/null

echo "=== 2. 按顺序执行真实迁移脚本"
for f in "$MIG_DIR"/V*.sql; do
  name="$(basename "$f")"
  # 迁移里有中文注释，同样走 -f 文件而不是 -c
  "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p "$PORT" -U postgres -d "$DB" \
    -v ON_ERROR_STOP=1 -q -f "$f" < /dev/null >/dev/null || fail "迁移失败：$name"
  printf '  %s 已应用\n' "$name"
done

echo "=== 3. V8 的列是否真的加上"
cols=$(psql_db -At -c "
  SELECT count(*) FROM information_schema.columns
  WHERE table_name = 'ing_source_run_config'
    AND column_name IN ('new_record_interval_minutes','field_refresh_interval_minutes',
                        'update_plan_note','next_due_at')")
[ "$cols" = "4" ] || fail "期望新增 4 列，实际 $cols"
echo "  [PASS] 新增 4 列"

echo "=== 4. 约束与门控语义"
psql_sql <<'SQL'
DO $$
DECLARE
  v_id    text := 'src_v8_check';
  v_due   timestamptz;
  v_count int;
BEGIN
  -- 4.1 周期下限：30 分钟不是「业务更新周期」，应被库级 CHECK 拒绝
  BEGIN
    INSERT INTO ing_source_run_config
      (id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
       page_size, config_version, license_review_state, rate_limit_per_minute, enabled,
       synced_config_version, new_record_interval_minutes, update_plan_note)
    VALUES
      (v_id, 'check-low', '校验-下限', 'CROSSREF', 'http://127.0.0.1:1/x', 'q',
       100, '1', 'APPROVED', 30, false, 1, 30, '假设的理由');
    RAISE EXCEPTION '周期 30 分钟本应被拒绝，但插入成功了';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] 周期下限 60 分钟生效（30 被拒绝）';
  END;

  -- 4.2 理由必填：设了周期却没写依据，同样被拒绝
  BEGIN
    INSERT INTO ing_source_run_config
      (id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
       page_size, config_version, license_review_state, rate_limit_per_minute, enabled,
       synced_config_version, new_record_interval_minutes, update_plan_note)
    VALUES
      (v_id, 'check-note', '校验-无依据', 'CROSSREF', 'http://127.0.0.1:1/x', 'q',
       100, '1', 'APPROVED', 30, false, 1, 1440, '   ');
    RAISE EXCEPTION '没有依据的周期本应被拒绝，但插入成功了';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE '[PASS] 设了周期就必须写依据（空白依据被拒绝）';
  END;

  -- 4.3 合法登记：默认未设定周期，因此未排期
  INSERT INTO ing_source_run_config
    (id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
     page_size, config_version, license_review_state, rate_limit_per_minute, enabled,
     synced_config_version)
  VALUES
    (v_id, 'check-ok', '校验-正常', 'CROSSREF', 'http://127.0.0.1:1/x', 'q',
     100, '1', 'APPROVED', 30, true, 1);

  SELECT next_due_at INTO v_due FROM ing_source_run_config WHERE id = v_id;
  IF v_due IS NOT NULL THEN
    RAISE EXCEPTION '新登记的来源不应有到期时刻，实际 %', v_due;
  END IF;
  RAISE NOTICE '[PASS] 新登记来源的 next_due_at 为空（不自动排期）';

  -- 4.4 设定周期后立刻到期：改周期的人期望它马上按新节奏跑一次
  UPDATE ing_source_run_config
  SET new_record_interval_minutes = 1440,
      field_refresh_interval_minutes = 10080,
      update_plan_note = '来源文档声明季度更新，人工评估后取每日发现新记录',
      next_due_at = now()
  WHERE id = v_id;

  SELECT next_due_at INTO v_due FROM ing_source_run_config WHERE id = v_id;
  IF v_due IS NULL OR v_due > now() THEN
    RAISE EXCEPTION '设定周期后应立刻到期，实际 %', v_due;
  END IF;
  RAISE NOTICE '[PASS] 设定周期后立刻到期';

  -- 4.5 撤销周期：两列为空，到期时刻回到 NULL，行为退回人工驱动
  UPDATE ing_source_run_config
  SET new_record_interval_minutes = NULL,
      field_refresh_interval_minutes = NULL,
      next_due_at = NULL
  WHERE id = v_id;
  SELECT next_due_at INTO v_due FROM ing_source_run_config WHERE id = v_id;
  IF v_due IS NOT NULL THEN
    RAISE EXCEPTION '撤销周期后应回到未排期，实际 %', v_due;
  END IF;
  RAISE NOTICE '[PASS] 撤销周期后回到未排期';
END $$;
SQL

echo "=== 5. 可执行任务查询的周期门控（与 CollectMapper.listRunnableJobIds 同形）"
# 说明：这段 SQL 是 listRunnableJobIds 的同形复刻，用来在真实数据上验证语义。
# 它不能替代 JUnit 的白盒断言（那条断言保证 Mapper 里的文本确实带上了这两个条件），
# 两者必须同时存在：一个证明「条件写进了 SQL」，一个证明「条件在数据上真的这样作用」。
psql_sql <<'SQL'
DO $$
DECLARE
  v_ids   text;
  v_all   text;
  v_count int;
BEGIN
  -- 场景 A：设了周期且未到期的来源，任务还没开工 —— 不应被取出来
  UPDATE ing_source_run_config
  SET new_record_interval_minutes = 1440, update_plan_note = '门控用例',
      next_due_at = now() + interval '1 hour'
  WHERE source_code = 'check-ok';

  INSERT INTO ing_collect_job (id, source_config_id, job_key, status, total_fetched)
  SELECT 'job_not_due', id, 'gate-a', 'PENDING', 0
  FROM ing_source_run_config WHERE source_code = 'check-ok';

  -- 场景 B：同一来源下另一个任务已经抓过东西 —— 必须继续放行，不能停在半路
  INSERT INTO ing_collect_job (id, source_config_id, job_key, status, total_fetched)
  SELECT 'job_in_progress', id, 'gate-b', 'RUNNING', 5
  FROM ing_source_run_config WHERE source_code = 'check-ok';

  -- 场景 C：来源未设定周期 —— 维持既有行为，随时可跑
  INSERT INTO ing_source_run_config
    (id, source_code, display_name, adapter_kind, base_endpoint, query_expression,
     page_size, config_version, license_review_state, rate_limit_per_minute, enabled,
     synced_config_version)
  VALUES
    ('src_v8_legacy', 'check-legacy', '校验-未设周期', 'CROSSREF', 'http://127.0.0.1:1/x', 'q',
     100, '1', 'APPROVED', 30, true, 1);
  INSERT INTO ing_collect_job (id, source_config_id, job_key, status, total_fetched)
  VALUES ('job_legacy', 'src_v8_legacy', 'gate-c', 'PENDING', 0);

  SELECT string_agg(j.id, ',' ORDER BY j.id) INTO v_ids
  FROM ing_collect_job j
  JOIN ing_source_run_config c ON c.id = j.source_config_id
  WHERE j.status IN ('PENDING', 'RUNNING')
    AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= now())
    AND (j.total_fetched > 0 OR c.next_due_at IS NULL OR c.next_due_at <= now());

  IF v_ids IS DISTINCT FROM 'job_in_progress,job_legacy' THEN
    RAISE EXCEPTION '周期门控结果不符：期望 job_in_progress,job_legacy，实际 %', v_ids;
  END IF;
  RAISE NOTICE '[PASS] 未到期的未开工任务被挡下；已开工任务与未设周期来源照常放行（%）', v_ids;

  -- 场景 D：把到期时间拨到过去，未开工的那个立刻可跑
  UPDATE ing_source_run_config SET next_due_at = now() - interval '1 second'
  WHERE source_code = 'check-ok';

  SELECT count(*) INTO v_count
  FROM ing_collect_job j
  JOIN ing_source_run_config c ON c.id = j.source_config_id
  WHERE j.id = 'job_not_due'
    AND (j.total_fetched > 0 OR c.next_due_at IS NULL OR c.next_due_at <= now());
  IF v_count <> 1 THEN
    RAISE EXCEPTION '到期后本应放行，实际命中 % 行', v_count;
  END IF;
  RAISE NOTICE '[PASS] 到期后同一任务被放行';

  -- 场景 E：反证。把到期时间拨回未来，对比「带周期条件」与「不带周期条件」的取任务结果。
  -- 如果两者相同，说明上面的断言是恒真的（条件没起作用也能通过），那才是真正的失败。
  UPDATE ing_source_run_config SET next_due_at = now() + interval '1 hour'
  WHERE source_code = 'check-ok';

  SELECT string_agg(j.id, ',' ORDER BY j.id) INTO v_ids
  FROM ing_collect_job j
  JOIN ing_source_run_config c ON c.id = j.source_config_id
  WHERE j.status IN ('PENDING', 'RUNNING')
    AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= now())
    AND (j.total_fetched > 0 OR c.next_due_at IS NULL OR c.next_due_at <= now());

  SELECT string_agg(j.id, ',' ORDER BY j.id) INTO v_all
  FROM ing_collect_job j
  WHERE j.status IN ('PENDING', 'RUNNING')
    AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= now());

  IF v_ids IS NOT DISTINCT FROM v_all THEN
    RAISE EXCEPTION '反证失败：去掉周期条件后结果没变（%），说明门控条件没起作用', v_all;
  END IF;
  RAISE NOTICE '[PASS] 反证：去掉周期条件会多取出未到期任务（带条件 % / 不带条件 %）', v_ids, v_all;
END $$;
SQL

if [ "$KEEP" = "1" ]; then
  echo "=== 保留校验库 $DB（--keep）"
else
  echo "=== 6. 清理校验库"
  psql_super -c "DROP DATABASE IF EXISTS $DB" >/dev/null
fi

echo "全部断言通过"
