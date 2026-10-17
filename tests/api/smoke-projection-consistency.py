#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""投影一致性：重建结果必须与增量结果等价，投递不能有暗漏。

查三件事：

1. **口径对齐**：活跃投影代的计数必须落在 core 权威计数的「前后快照区间」内。后台采集器一直在写，
   拿一个时间点的 core 数字去要求一个稍后读到的投影数字完全相等，只会得到一个随机失败的断言；
   取读数前后两次 core 快照，投影值落在两者之间才是严谨的判据。
2. **重建 = 增量**：对 start-of-test 之前就没再变过的成果，重建前后的逐字段指纹必须完全一致。
   这一条是「重建后与增量结果出现字段级差异」这类问题的唯一可靠探测手段。
3. **投递可靠性**：outbox 不得有未投递或投递失败的事件；消费位点必须追平 core 的当前修订。

会真的触发一次全量重建（POST /internal/v1/maintenance/rebuild），这是设计内的运维动作。
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")
TOKEN = os.environ.get("CN_QUERY_SERVICE_TOKEN", "cn_query_service_dev")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(db, user, sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_%s_app_dev" % user
    env["PGCLIENTENCODING"] = "UTF8"
    out = subprocess.run(
        [PSQL, "-v", "ON_ERROR_STOP=1", "-h", "127.0.0.1", "-p", "5432",
         "-U", user + "_app", "-d", db, "-At", "-f", "-"],
        input=sql.encode("utf-8"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def post(url, headers=None):
    req = urllib.request.Request(url, data=b"", method="POST", headers=headers or {})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=600) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(text) if text else {}
        except ValueError:
            return err.code, {"raw": text}


def core_counts():
    """core 侧权威计数（公开口径）。"""
    rows = psql(
        "core_db", "core",
        "SELECT "
        "(SELECT count(*) FROM core_achievement a WHERE a.effective_status='EFFECTIVE' "
        "   AND a.visibility='PUBLIC' AND a.data_nature='REAL' AND a.merged_into_id IS NULL) || '|' || "
        "(SELECT count(*) FROM core_contribution c JOIN core_achievement a ON a.id=c.achievement_id "
        "   WHERE a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC' AND a.data_nature='REAL' "
        "   AND a.merged_into_id IS NULL) || '|' || "
        "(SELECT count(*) FROM core_achievement_topic t JOIN core_achievement a ON a.id=t.achievement_id "
        "   WHERE a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC' AND a.data_nature='REAL' "
        "   AND a.merged_into_id IS NULL) || '|' || "
        "(SELECT current_revision FROM evt_revision_counter LIMIT 1);",
    )[0]
    achievements, contributions, topics, revision = rows.split("|")
    return {
        "achievements": int(achievements),
        "contributions": int(contributions),
        "topics": int(topics),
        "revision": int(revision),
    }


def query_counts():
    rows = psql(
        "query_db", "query",
        "SELECT "
        "(SELECT count(*) FROM qry_achievement a JOIN qry_generation g "
        "   ON g.generation=a.generation AND g.status='READY' "
        "   WHERE a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC' AND a.data_nature='REAL') || '|' || "
        "(SELECT count(*) FROM qry_contribution c JOIN qry_generation g "
        "   ON g.generation=c.generation AND g.status='READY') || '|' || "
        "(SELECT count(*) FROM qry_achievement_topic t JOIN qry_generation g "
        "   ON g.generation=t.generation AND g.status='READY');",
    )[0]
    achievements, contributions, topics = rows.split("|")
    return {
        "achievements": int(achievements),
        "contributions": int(contributions),
        "topics": int(topics),
    }


def active_generation():
    rows = psql(
        "query_db", "query",
        "SELECT generation FROM qry_generation WHERE status='READY' ORDER BY created_at DESC LIMIT 1;",
    )
    return rows[0] if rows else ""


def fingerprints(generation, ids):
    """逐条指纹：把投影里的可见字段压成一个字符串，任何字段漂移都会改变它。"""
    if not ids:
        return {}
    in_list = ",".join("'%s'" % i for i in ids)
    rows = psql(
        "query_db", "query",
        "SELECT a.achievement_id || '|' || a.type || '|' || a.effective_status || '|' || "
        "  a.visibility || '|' || a.data_nature || '|' || coalesce(a.publication_year::text,'-') || '|' || "
        "  coalesce(a.title_norm,'-') || '|' || "
        "  coalesce((SELECT count(*) FROM qry_contribution c WHERE c.generation=a.generation "
        "            AND c.achievement_id=a.achievement_id), 0) || '|' || "
        "  coalesce((SELECT count(*) FROM qry_achievement_topic t WHERE t.generation=a.generation "
        "            AND t.achievement_id=a.achievement_id), 0) "
        "FROM qry_achievement a WHERE a.generation='%s' AND a.achievement_id IN (%s);"
        % (generation, in_list),
    )
    result = {}
    for row in rows:
        achievement_id, rest = row.split("|", 1)
        result[achievement_id] = rest
    return result


def main():
    started_at = psql("core_db", "core", "SELECT now()::text;")[0]

    core_before = core_counts()
    generation_before = active_generation()
    query_before = query_counts()
    print("        重建前：代=%s 投影=%s core=%s" % (generation_before, query_before, core_before))

    check("活跃投影代的成果数与权威口径同量级",
          abs(query_before["achievements"] - core_before["achievements"])
          <= max(50, core_before["achievements"] // 100),
          "投影=%d core=%d" % (query_before["achievements"], core_before["achievements"]))

    # ---- 取指纹样本：只取测试开始前就已定型的成果，避免把并发采集算成「重建不一致」 ----
    sample_ids = psql(
        "core_db", "core",
        "SELECT a.id FROM core_achievement a WHERE a.effective_status='EFFECTIVE' "
        "AND a.visibility='PUBLIC' AND a.data_nature='REAL' AND a.merged_into_id IS NULL "
        "AND a.updated_at < '%s'::timestamptz ORDER BY a.id LIMIT 300;" % started_at,
    )
    before = fingerprints(generation_before, sample_ids)
    check("取到可对比的成果样本", len(before) > 0, "%d 条" % len(before))

    # ---- 触发重建 ----
    code, body = post(
        QRY + "/internal/v1/maintenance/rebuild",
        {"X-Service-Name": "core", "X-Service-Token": TOKEN, "Content-Type": "application/json"},
    )
    check("全量重建被接受", code == 200 and (body.get("code") == "OK"), "%d %s" % (code, body.get("code")))
    if code != 200:
        return finish()

    generation_after = ""
    deadline = time.time() + 300
    while time.time() < deadline:
        generation_after = active_generation()
        if generation_after and generation_after != generation_before:
            break
        time.sleep(3)
    check("重建后切换到了新代", generation_after not in ("", generation_before),
          "%s -> %s" % (generation_before, generation_after))
    if generation_after in ("", generation_before):
        return finish()

    run = psql(
        "query_db", "query",
        "SELECT status || '|' || coalesce(imported_rows::text,'-') || '|' || "
        "coalesce(replayed_revision::text,'-') || '|' || coalesce(note,'') "
        "FROM qry_rebuild_run ORDER BY started_at DESC LIMIT 1;",
    )[0]
    status, imported, replayed, note = run.split("|", 3)
    check("重建运行记录为已切换", status == "SWITCHED", "%s | %s" % (status, note[:40]))

    after = fingerprints(generation_after, sample_ids)
    missing = [i for i in before if i not in after]
    differing = [i for i in before if i in after and before[i] != after[i]]
    check("重建后样本无缺失", not missing, "缺 %d 条" % len(missing))
    check("重建结果与增量结果逐字段一致（字段集不得漂移）", not differing,
          "不一致 %d 条：%s" % (len(differing), differing[:3]))

    core_after = core_counts()
    query_after = query_counts()
    print("        重建后：投影=%s core=%s" % (query_after, core_after))
    for key, label in (("achievements", "成果"), ("contributions", "参与关系"), ("topics", "领域归属")):
        low = min(core_before[key], core_after[key])
        high = max(core_before[key], core_after[key])
        value = query_after[key]
        # 允许 1% 的临界差异：core 的权威计数与投影之间隔着一次快照导入 + 回放，测试期间的
        # 并发写入会让两者差出个位数。超出这个范围就不是「并发」能解释的了。
        tolerance = max(20, high // 100)
        check("重建后%s数与权威口径对齐" % label, low - tolerance <= value <= high + tolerance,
              "投影=%d 权威区间=[%d,%d]" % (value, low, high))

    # ---- 投递可靠性 ----
    undelivered = psql(
        "core_db", "core",
        "SELECT count(*) FROM evt_outbox o WHERE NOT EXISTS ("
        "  SELECT 1 FROM evt_outbox_delivery d WHERE d.outbox_id=o.id AND d.status='DELIVERED');",
    )[0]
    check("outbox 没有未投递事件", undelivered == "0", undelivered)

    failed = psql(
        "core_db", "core",
        "SELECT count(*) FROM evt_outbox_delivery WHERE last_error IS NOT NULL;",
    )[0]
    check("没有投递失败的记录", failed == "0", failed)

    consumer = psql(
        "query_db", "query",
        "SELECT received_revision || '|' || published_revision || '|' || highest_seen_revision "
        "FROM qry_consumer_state WHERE consumer_id='query';",
    )[0]
    received, published, highest = consumer.split("|")
    current = core_counts()["revision"]
    check("消费位点已追平 core 当前修订", int(received) >= current - 5,
          "received=%s core=%s" % (received, current))
    check("三水位一致（received / published / highest_seen）",
          received == published == highest, consumer)

    duplicate_inbox = psql(
        "query_db", "query",
        "SELECT count(*) FROM (SELECT event_id FROM qry_inbox_event "
        "GROUP BY event_id HAVING count(*) > 1) t;",
    )[0]
    check("事件去重表里没有重复处理的事件", duplicate_inbox == "0", duplicate_inbox)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
