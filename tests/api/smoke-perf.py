#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""接口时延基线（单用户、串行）。

**这份结果不能用来宣称「P95≤2 秒」达标。** 架构里那条目标是 500 万条可检索成果 / 100 活跃并发
下的验收指标，而这里测的是当前本地库（约 1 万条公开成果、单个调用者、无并发排队）。它回答的是
另一个问题：在现有数据量下，每条查询链路的耗时构成是否正常，有没有哪一条慢到不像同一个系统。

因此这里的判据是「不得超过 2 秒这条工程上限」，并且逐条打印 P50/P95/P99 与最大值，让读者自己
判断余量；绝不在结论里写成验收通过。
"""

import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
QRY = os.environ.get("QRY_BASE", "http://127.0.0.1:8083")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")
ROUNDS = int(os.environ.get("PERF_ROUNDS", "30"))
CEILING_MS = 2000.0

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
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:300])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def timed(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    started = time.perf_counter()
    try:
        with opener.open(req, timeout=30) as resp:
            body = resp.read()
            status = resp.status
    except urllib.error.HTTPError as err:
        body = err.read()
        status = err.code
    return (time.perf_counter() - started) * 1000.0, status, body


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(p / 100.0 * (len(ordered) - 1)))))
    return ordered[index]


def measure(label, url, rounds=ROUNDS):
    # 预热一次，避免把首次请求的类加载、连接建立、计划缓存算进分位数。
    timed(url)
    samples = []
    failures = 0
    for _ in range(rounds):
        ms, status, body = timed(url)
        if status == 200:
            samples.append(ms)
        else:
            failures += 1
    p50 = percentile(samples, 50)
    p95 = percentile(samples, 95)
    p99 = percentile(samples, 99)
    worst = max(samples) if samples else 0.0
    print(
        "        %-22s n=%2d P50=%6.1fms P95=%6.1fms P99=%6.1fms max=%6.1fms 失败=%d"
        % (label, len(samples), p50, p95, p99, worst, failures)
    )
    check("%s 全部成功返回" % label, failures == 0, "%d 次失败" % failures)
    check("%s 的 P95 不超过 2 秒工程上限" % label, p95 <= CEILING_MS, "P95=%.0fms" % p95)
    return {"label": label, "p50": p50, "p95": p95, "p99": p99, "max": worst, "n": len(samples)}


def main():
    scale = psql(
        "query_db", "query",
        "SELECT count(*) FROM qry_achievement a JOIN qry_generation g "
        "ON g.generation=a.generation AND g.status='READY';",
    )[0]
    scholars = psql(
        "query_db", "query",
        "SELECT count(DISTINCT scholar_id) FROM qry_contribution c JOIN qry_generation g "
        "ON g.generation=c.generation AND g.status='READY';",
    )[0]
    edges = psql(
        "query_db", "query",
        "SELECT count(*) FROM qry_coauthor_edge e JOIN qry_generation g "
        "ON g.generation=e.generation AND g.status='READY';",
    )[0]
    print("        当前规模：成果 %s 条，学者 %s 位，合作边 %s 条" % (scale, scholars, edges))

    sample_title = psql(
        "query_db", "query",
        "SELECT a.title FROM qry_achievement a JOIN qry_generation g "
        "ON g.generation=a.generation AND g.status='READY' "
        "WHERE a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC' LIMIT 1;",
    )[0]
    hub = psql(
        "query_db", "query",
        "SELECT e.scholar_a FROM qry_coauthor_edge e JOIN qry_generation g "
        "ON g.generation=e.generation AND g.status='READY' "
        "GROUP BY 1 ORDER BY count(*) DESC LIMIT 1;",
    )[0]
    achievement_id = psql(
        "query_db", "query",
        "SELECT a.achievement_id FROM qry_achievement a JOIN qry_generation g "
        "ON g.generation=a.generation AND g.status='READY' "
        "WHERE a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC' LIMIT 1;",
    )[0]

    results = []
    results.append(measure("关键词检索", QRY + "/api/v1/public/search/achievements?q=%s&size=20"
                           % urllib.parse.quote(sample_title[:60])))
    results.append(measure("列表翻页", QRY + "/api/v1/public/search/achievements?size=20&page=3"))
    results.append(measure("分面筛选", QRY + "/api/v1/public/search/achievements?type=PAPER&yearFrom=2020&size=20"))
    results.append(measure("统计概览", QRY + "/api/v1/public/analytics/overview"))
    topic_id = psql(
        "query_db", "query",
        "SELECT t.topic_id FROM qry_achievement_topic t JOIN qry_generation g "
        "ON g.generation=t.generation AND g.status='READY' "
        "GROUP BY 1 ORDER BY count(*) DESC LIMIT 1;",
    )[0]
    results.append(measure("领域洞察", QRY + "/api/v1/public/analytics/topics/%s" % topic_id))
    results.append(measure("合作网络 1 跳", QRY + "/api/v1/public/network/graph?scholarId=%s&depth=1" % hub))
    results.append(measure("合作网络 2 跳", QRY + "/api/v1/public/network/graph?scholarId=%s&depth=2" % hub,
                           rounds=max(10, ROUNDS // 2)))
    results.append(measure("成果详情", CORE + "/api/v1/public/achievements/%s" % achievement_id,
                           rounds=max(10, ROUNDS // 2)))

    worst = max(results, key=lambda r: r["p95"])
    print("\n        最慢的一条：%s（P95=%.0fms）" % (worst["label"], worst["p95"]))
    check("所有链路的 P95 都留有明显余量（不足上限的一半）", worst["p95"] <= CEILING_MS / 2,
          "%.0fms / %.0fms" % (worst["p95"], CEILING_MS))

    print("\n        说明：以上为单用户串行实测，规模 %s 条，远低于 500 万条 / 100 并发的验收条件，"
          "不得据此宣称性能验收通过。" % scale)
    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
