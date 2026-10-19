#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学者档案指标（年份分布 / 主要合作者）端到端自检。

验证的不是「接口有 metrics 字段」，而是这些产品规则：

1. 口径与 SQL 逐项对上：年份分布、年份未知数、主要合作者的共同成果数，全部与「只算关系合格、
   成果公开生效未合并」的独立查询一致。差一条就说明页面上的数字不能对外说；
2. 待核验 / 争议 / 已排除的关系**不计入**任何指标——这是「同名不同人」不串账的唯一保障；
3. `worksTotal = worksWithYear + worksWithoutYear`，年份未知不会被折算到某一年；
4. 响应里没有任何评价性指标（h-index / impact factor / score 之类），只有可核对的计数；
5. 指标与 `contributions` 列表同口径：合格关系条数 == `metrics.worksTotal`。

前置：整栈已启动（bash ops/local/stack.sh）。
只读，不写任何数据。

用法：
  python tests/api/smoke-scholar-metrics.py
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def skip(name, why):
    print("  SKIP  " + name + "  -> " + why)


def psql(db, user, sql, sep="\t"):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_%s_app_dev" % user
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", user + "_app", "-d", db, "-At", "-F", sep,
         "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


def get_json(path):
    req = urllib.request.Request(CORE + path, headers={"Accept": "application/json"}, method="GET")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as err:
        payload = err.read()
        try:
            return err.code, json.loads(payload.decode("utf-8")) if payload else {}
        except ValueError:
            return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}


QUALIFIED = "c.relation_state IN ('AUTO', 'SELF_CONFIRMED')"
PUBLIC_WORK = (
    "a.visibility = 'PUBLIC' AND a.effective_status = 'EFFECTIVE' AND a.merged_into_id IS NULL"
)


def main():
    # ------------------------------------------------------------------
    # 0. 挑一位「既有合格关系、又有待核验关系」的学者：这样才能验证后者不计入
    # ------------------------------------------------------------------
    rows = psql(
        "core_db",
        "core",
        """
        SELECT s.id, s.display_name,
               count(*) FILTER (WHERE c.relation_state IN ('AUTO', 'SELF_CONFIRMED')),
               count(*) FILTER (WHERE c.relation_state IN ('PENDING_VERIFY', 'DISPUTED', 'EXCLUDED'))
        FROM core_scholar s
        JOIN core_contribution c ON c.scholar_id = s.id
        JOIN core_achievement a ON a.id = c.achievement_id
        WHERE s.merged_into_id IS NULL AND %s
        GROUP BY s.id, s.display_name
        HAVING count(*) FILTER (WHERE c.relation_state IN ('AUTO', 'SELF_CONFIRMED')) >= 2
        ORDER BY count(*) FILTER (WHERE c.relation_state IN ('PENDING_VERIFY', 'DISPUTED', 'EXCLUDED')) DESC,
                 count(*) FILTER (WHERE c.relation_state IN ('AUTO', 'SELF_CONFIRMED')) DESC
        LIMIT 1;
        """ % PUBLIC_WORK,
    )
    if not rows:
        print("没有可用于自检的学者（需要至少 2 条合格关系）。")
        return 1
    scholar_id, name, qualified_n, unqualified_n = rows[0].split("\t")
    print("目标学者：%s（%s），合格关系 %s 条，待核验/争议/排除 %s 条"
          % (name, scholar_id, qualified_n, unqualified_n))

    code, payload = get_json("/api/v1/public/scholars/%s" % scholar_id)
    data = (payload or {}).get("data") or {}
    check("学者详情可读", code == 200 and data.get("id") == scholar_id, code)

    metrics = data.get("metrics")
    check("响应带 metrics", isinstance(metrics, dict), type(metrics).__name__)
    if not isinstance(metrics, dict):
        return 1

    # ------------------------------------------------------------------
    # 1. 结构：字段齐全、口径说明随数据下发、无评价性指标
    # ------------------------------------------------------------------
    print("== 1 结构与口径声明 ==")
    for key in ("worksTotal", "worksWithYear", "worksWithoutYear", "byYear", "topCoauthors", "basis", "note"):
        check("字段存在 %s" % key, key in metrics, sorted(metrics.keys()))
    check("口径说明非空", bool(str(metrics.get("basis") or "").strip()), metrics.get("basis"))
    check("无评价性指标（h-index / 影响因子 / 评分）",
          not any(k for k in metrics if any(w in k.lower() for w in ("hindex", "h_index", "impact", "score", "rank"))),
          sorted(metrics.keys()))
    check("说明里明确「不给综合评分」", "评分" in str(metrics.get("note") or "") or "评级" in str(metrics.get("note") or ""),
          metrics.get("note"))

    # ------------------------------------------------------------------
    # 2. 年份分布与 SQL 逐项对照
    # ------------------------------------------------------------------
    print("== 2 年份分布 ==")
    expected_years = []
    for line in psql(
        "core_db",
        "core",
        """
        SELECT a.publication_year, count(*)
        FROM core_achievement a
        JOIN core_contribution c ON c.achievement_id = a.id
        WHERE c.scholar_id = '%s' AND %s AND %s AND a.publication_year IS NOT NULL
        GROUP BY a.publication_year ORDER BY a.publication_year;
        """ % (scholar_id, QUALIFIED, PUBLIC_WORK),
    ):
        year, amount = line.split("\t")
        expected_years.append({"year": int(year), "amount": int(amount)})

    check("年份桶与 SQL 完全一致", metrics.get("byYear") == expected_years,
          (metrics.get("byYear"), expected_years))
    check("年份桶按升序排列",
          [row["year"] for row in (metrics.get("byYear") or [])]
          == sorted(row["year"] for row in (metrics.get("byYear") or [])),
          metrics.get("byYear"))

    expected_known = sum(row["amount"] for row in expected_years)
    check("worksWithYear 等于各年份之和", metrics.get("worksWithYear") == expected_known,
          (metrics.get("worksWithYear"), expected_known))

    expected_unknown = int(
        psql(
            "core_db",
            "core",
            """
            SELECT count(*) FROM core_achievement a
            JOIN core_contribution c ON c.achievement_id = a.id
            WHERE c.scholar_id = '%s' AND %s AND %s AND a.publication_year IS NULL;
            """ % (scholar_id, QUALIFIED, PUBLIC_WORK),
        )[0]
    )
    check("worksWithoutYear 与 SQL 一致",
          metrics.get("worksWithoutYear") == expected_unknown,
          (metrics.get("worksWithoutYear"), expected_unknown))
    check("worksTotal = 有年份 + 年份未知",
          metrics.get("worksTotal") == expected_known + expected_unknown,
          (metrics.get("worksTotal"), expected_known + expected_unknown))

    # ------------------------------------------------------------------
    # 3. 待核验 / 争议 / 已排除不计入
    # ------------------------------------------------------------------
    print("== 3 未确认关系不计入 ==")
    check("worksTotal 等于合格关系数（不是全部关系数）",
          metrics.get("worksTotal") == int(qualified_n),
          (metrics.get("worksTotal"), qualified_n))
    if int(unqualified_n) > 0:
        check("存在待核验关系时总数仍只算合格的",
              metrics.get("worksTotal") != int(qualified_n) + int(unqualified_n),
              (metrics.get("worksTotal"), int(qualified_n) + int(unqualified_n)))
    else:
        skip("存在待核验关系时总数仍只算合格的", "该学者名下没有待核验/争议关系")

    # ------------------------------------------------------------------
    # 4. 主要合作者
    # ------------------------------------------------------------------
    print("== 4 主要合作者 ==")
    expected_coauthors = []
    for line in psql(
        "core_db",
        "core",
        """
        SELECT s.id, count(*)
        FROM core_contribution me
        JOIN core_achievement a ON a.id = me.achievement_id
        JOIN core_contribution other
          ON other.achievement_id = me.achievement_id AND other.scholar_id <> me.scholar_id
        JOIN core_scholar s ON s.id = other.scholar_id
        WHERE me.scholar_id = '%s'
          AND me.relation_state IN ('AUTO', 'SELF_CONFIRMED')
          AND other.relation_state IN ('AUTO', 'SELF_CONFIRMED')
          AND s.merged_into_id IS NULL AND %s
        GROUP BY s.id ORDER BY count(*) DESC, s.id LIMIT %d;
        """ % (scholar_id, PUBLIC_WORK, metrics.get("coauthorLimit") or 10),
    ):
        cid, shared = line.split("\t")
        expected_coauthors.append({"scholarId": cid, "shared": int(shared)})

    actual = [
        {"scholarId": row.get("scholarId"), "shared": row.get("sharedAchievements")}
        for row in (metrics.get("topCoauthors") or [])
    ]
    check("合作者名单与 SQL 一致（含共同成果数）", actual == expected_coauthors,
          (actual[:3], expected_coauthors[:3]))
    check("合作者按共同成果数降序",
          [row["shared"] for row in actual] == sorted((row["shared"] for row in actual), reverse=True),
          [row["shared"] for row in actual])
    check("不包含学者本人", all(row["scholarId"] != scholar_id for row in actual), actual[:3])

    # ------------------------------------------------------------------
    # 5. 与 contributions 列表同口径
    # ------------------------------------------------------------------
    print("== 5 与列表同口径 ==")
    rows_list = data.get("contributions") or []
    qualified_rows = [
        row for row in rows_list
        if row.get("qualified") is True
        or row.get("relationState") in ("AUTO", "SELF_CONFIRMED")
    ]
    check("列表条数等于合格关系数", len(qualified_rows) == int(qualified_n),
          (len(qualified_rows), qualified_n))
    check("metrics.worksTotal 与列表合格条数一致",
          metrics.get("worksTotal") == len(qualified_rows),
          (metrics.get("worksTotal"), len(qualified_rows)))

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
