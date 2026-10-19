#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学者引用量（累计 / 篇均 / 按发表年份 / 来源）端到端自检。

要证明的不是「接口有 citations 字段」，而是这几个数字能不能对外说：

1. **与 SQL 逐项对上**：累计引用、有数据条数、单篇最高、按年份的分布，全部与利用 core_db
   独立算出的值一致。差一个就说明页面上的数字不可信；
2. **缺失不补 0**：篇均只除以「确有引用量记录」的成果数。用 psql 算 total/with_citation
   与接口比对，若实现把缺失当 0 参与平均，这里会直接暴露（分母变成 worksTotal）；
3. **非论文单列**：专利/项目/获奖类型上没有引用量概念，不能被算成「数据缺失」，
   也不能进篇均分母；
4. **一条数据都没有时不编数**：篇均字段必须缺席（服务端 non_null 序列化），
   而不是 0；0 会被读成「平均没人引用」；
5. **跨服务闭合**：把该学者名下每篇成果的详情逐个取出来，其 citationCount 的最大值
   必须等于聚合里的单篇最高——聚合不是另外一套算法；
6. **没有评价性指标**：响应里不得出现 h-index / impact factor / score 这类字段。

前置：整栈已启动（bash ops/local/stack.sh）。
只读，不写任何数据。

用法：python tests/api/smoke-scholar-citations.py
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from decimal import ROUND_HALF_UP, Decimal

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")

RESULTS = []


def average(total, count):
    """与后端一致的四舍五入（HALF_UP）。

    Python 内置 round() 用的是「四舍六入五取偶」：12221/8 = 1527.625 会被舍成 1527.62，
    而后端 BigDecimal.HALF_UP 给 1527.63。用内置 round 比对，会在恰好落在 .xx5 的年份上
    报出一个根本不存在的差异。
    """
    if not count:
        return None
    return float((Decimal(total) / Decimal(count)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(sql, sep="\t"):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db",
         "-At", "-F", sep, "-v", "ON_ERROR_STOP=1", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


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
    # 0. 挑一位引用量最高的学者：分布足够长，byYear 才有东西可核
    # ------------------------------------------------------------------
    rows = psql(
        """
        SELECT c.scholar_id, coalesce(sum(p.citation_count), 0) AS total
        FROM core_contribution c
        JOIN core_achievement a ON a.id = c.achievement_id
        LEFT JOIN core_paper p ON p.achievement_id = a.id
        WHERE %s AND %s
        GROUP BY c.scholar_id
        ORDER BY total DESC
        LIMIT 1
        """
        % (QUALIFIED, PUBLIC_WORK)
    )
    if not rows:
        print("库里没有可用样本，跳过")
        return 0
    scholar_id = rows[0].split("\t")[0]

    status, body = get_json("/api/v1/public/scholars/" + scholar_id)
    check("学者详情可取", status == 200, status)
    data = body.get("data") or {}
    metrics = data.get("metrics") or {}
    citations = metrics.get("citations") or {}
    check("详情含引用量区块", isinstance(citations, dict) and bool(citations), list(citations)[:4])
    if not citations:
        return 1

    # ------------------------------------------------------------------
    # 1. 汇总数字与 SQL 逐项对上
    # ------------------------------------------------------------------
    print("== 1 汇总与 SQL 对齐 ==")
    summary = psql(
        """
        SELECT count(*),
               count(p.citation_count),
               count(*) FILTER (WHERE a.type <> 'PAPER'),
               coalesce(sum(p.citation_count), 0),
               coalesce(max(p.citation_count), 0)
        FROM core_contribution c
        JOIN core_achievement a ON a.id = c.achievement_id
        LEFT JOIN core_paper p ON p.achievement_id = a.id
        WHERE c.scholar_id = '%s' AND %s AND %s
        """
        % (scholar_id, QUALIFIED, PUBLIC_WORK)
    )[0].split("\t")
    works, with_cit, non_paper, total, max_cit = (int(value) for value in summary)
    check("成果数与 SQL 一致", citations.get("worksTotal") == works, (citations.get("worksTotal"), works))
    check("有引用量记录数与 SQL 一致",
          citations.get("worksWithCitation") == with_cit, (citations.get("worksWithCitation"), with_cit))
    check("累计引用与 SQL 一致",
          citations.get("totalCitations") == total, (citations.get("totalCitations"), total))
    check("单篇最高与 SQL 一致",
          citations.get("maxWorkCitations") == max_cit, (citations.get("maxWorkCitations"), max_cit))
    check("非论文成果单列且与 SQL 一致",
          citations.get("worksNotCitable") == non_paper, (citations.get("worksNotCitable"), non_paper))

    # ------------------------------------------------------------------
    # 2. 篇均：分母必须是有数据的条数（把缺失当 0 的实现会在这里露馅）
    # ------------------------------------------------------------------
    print("== 2 篇均口径 ==")
    expected_avg = average(total, with_cit)
    if with_cit:
        check("篇均 = 累计 / 有数据条数", citations.get("averagePerWork") == expected_avg,
              (citations.get("averagePerWork"), expected_avg))
        # 反面：若实现用了 worksTotal 作分母，值会明显更小；这里把「错误算法」也写出来对比。
        wrong_avg = average(total, works) or 0
        check("篇均不是用成果总数作分母（错误算法会给出另一个值）",
              citations.get("averagePerWork") != wrong_avg or expected_avg == wrong_avg,
              (citations.get("averagePerWork"), wrong_avg))
        check("覆盖率 = 有数据条数 / 成果数",
              citations.get("coveragePercent") == round(with_cit * 100.0 / works, 1),
              (citations.get("coveragePercent"), round(with_cit * 100.0 / works, 1)))

    # ------------------------------------------------------------------
    # 3. 缺失项拆分：论文缺来源数据 vs 类型上没有引用量
    # ------------------------------------------------------------------
    print("== 3 缺失拆分 ==")
    missing = int(citations.get("worksMissingCitation") or 0)
    paper_missing = int(citations.get("worksPaperMissingCitation") or 0)
    check("缺失总数 = 成果数 - 有数据条数", missing == works - with_cit, (missing, works - with_cit))
    check("论文缺失 + 非论文 = 缺失总数", paper_missing + non_paper == missing,
          (paper_missing, non_paper, missing))

    # ------------------------------------------------------------------
    # 4. 按发表年份的分布：年份桶之和 + 年份未知 = 总数
    # ------------------------------------------------------------------
    print("== 4 按发表年份 ==")
    by_year = citations.get("byYear") or []
    check("按年份分组非空", len(by_year) > 0, len(by_year))
    sql_years = {}
    for line in psql(
        """
        SELECT a.publication_year, count(*), count(p.citation_count), coalesce(sum(p.citation_count), 0)
        FROM core_contribution c
        JOIN core_achievement a ON a.id = c.achievement_id
        LEFT JOIN core_paper p ON p.achievement_id = a.id
        WHERE c.scholar_id = '%s' AND %s AND %s AND a.publication_year IS NOT NULL
        GROUP BY a.publication_year
        """
        % (scholar_id, QUALIFIED, PUBLIC_WORK)
    ):
        year, y_works, y_with, y_cit = line.split("\t")
        sql_years[int(year)] = (int(y_works), int(y_with), int(y_cit))

    mismatched = [
        row for row in by_year
        if sql_years.get(row.get("year")) != (
            row.get("works"), row.get("worksWithCitation"), row.get("citations")
        )
    ]
    check("每个年份桶的 (成果数, 有数据数, 引用量) 都与 SQL 一致", not mismatched,
          mismatched[:2] if mismatched else len(by_year))
    check("年份桶数与 SQL 一致", len(by_year) == len(sql_years), (len(by_year), len(sql_years)))

    with_year = sum(int(row.get("works") or 0) for row in by_year)
    undated = int(metrics.get("worksWithoutYear") or 0)
    check("年份已知 + 年份未知 = 成果总数", with_year + undated == works, (with_year, undated, works))
    check("各年引用量之和不超过累计引用（年份未知的成果不在图里）",
          sum(int(row.get("citations") or 0) for row in by_year) <= total,
          (sum(int(row.get("citations") or 0) for row in by_year), total))

    bad_avg = [
        row for row in by_year
        if row.get("worksWithCitation")
        and row.get("averagePerWork") != average(int(row["citations"]), int(row["worksWithCitation"]))
    ]
    check("每年的篇均同样只除以该年有数据的条数", not bad_avg, bad_avg[:2] if bad_avg else len(by_year))

    # ------------------------------------------------------------------
    # 5. 来源分布：cover 到全部有数据的成果
    # ------------------------------------------------------------------
    print("== 5 来源分布 ==")
    sources = citations.get("sources") or []
    check("来源分布非空", len(sources) > 0, sources)
    check("来源条数之和 = 有引用量记录的成果数",
          sum(int(row.get("works") or 0) for row in sources) == with_cit,
          (sum(int(row.get("works") or 0) for row in sources), with_cit))
    check("来源名在已知集合内",
          all(row.get("source") in ("OpenAlex", "Crossref", "来源未注明") for row in sources),
          [row.get("source") for row in sources])

    # ------------------------------------------------------------------
    # 6. 跨服务闭合：详情页单篇引用量的最大值 = 聚合里的单篇最高
    # ------------------------------------------------------------------
    print("== 6 跨服务闭合 ==")
    contribution_ids = [
        row.get("achievementId") for row in (data.get("contributions") or [])
        if row.get("achievementId")
        and (row.get("qualified") is True
             or row.get("relationState") in ("AUTO", "SELF_CONFIRMED"))
    ]
    if len(contribution_ids) > 60:
        # 成果太多时只抽一部分：这条断言要证明的是「聚合与详情同一套值」，不是穷举。
        contribution_ids = contribution_ids[:60]
    detail_counts = []
    for achievement_id in contribution_ids:
        status, detail = get_json("/api/v1/public/achievements/" + achievement_id)
        if status != 200:
            continue
        paper = (detail.get("data") or {}).get("paper") or {}
        value = paper.get("citationCount")
        if value is not None:
            detail_counts.append(int(value))
    if detail_counts:
        check("聚合的单篇最高 = 详情里的最大值", max(detail_counts) == max_cit,
              (max(detail_counts), max_cit))
        check("聚合的累计引用 = 详情逐个相加",
              sum(detail_counts) == total, (sum(detail_counts), total))
    else:
        print("  SKIP  抽到的成果均无引用量，跳过闭合校验")

    # ------------------------------------------------------------------
    # 7. 不编评价性指标；口径说明必须在
    # ------------------------------------------------------------------
    print("== 7 口径与红线 ==")

    def all_keys(node):
        if isinstance(node, dict):
            for key, value in node.items():
                yield key
                yield from all_keys(value)
        elif isinstance(node, list):
            for item in node:
                yield from all_keys(item)

    # 按字段名判，而不是按文本判：说明文字里出现「不提供 h-index」是正常的，
    # 真正要拦的是「把 h-index 当成一个字段返回」。
    forbidden_keys = [
        key for key in all_keys(metrics)
        if any(word in key.lower() for word in ("hindex", "h_index", "impact", "score", "rating"))
    ]
    check("metrics 里没有评价性指标字段", not forbidden_keys, forbidden_keys or "（无）")
    check("趋势说明写明「不是每年新增引用」",
          "不是「每年新增引用」" in (citations.get("trendNote") or ""), citations.get("trendNote"))
    check("来源说明写明跨来源不可直接相加",
          "跨来源求和只能作为量级参考" in (citations.get("sourceNote") or ""))
    check("口径说明写明缺失不补 0",
          "不补 0" in (citations.get("basis") or ""), citations.get("basis"))
    check("明确不提供 h-index / 影响因子",
          "h-index" in (citations.get("metricNote") or ""))

    # ------------------------------------------------------------------
    # 8. 一条数据都没有的学者：不编数
    # ------------------------------------------------------------------
    print("== 8 无数据学者 ==")
    empty_rows = psql(
        """
        SELECT c.scholar_id
        FROM core_contribution c
        JOIN core_achievement a ON a.id = c.achievement_id
        LEFT JOIN core_paper p ON p.achievement_id = a.id
        WHERE %s AND %s
        GROUP BY c.scholar_id
        HAVING count(p.citation_count) = 0 AND count(*) FILTER (WHERE a.type <> 'PAPER') > 0
        LIMIT 1
        """
        % (QUALIFIED, PUBLIC_WORK)
    )
    if empty_rows:
        empty_id = empty_rows[0]
        status, empty_body = get_json("/api/v1/public/scholars/" + empty_id)
        empty_citations = ((empty_body.get("data") or {}).get("metrics") or {}).get("citations") or {}
        check("无引用量记录时 worksWithCitation = 0",
              empty_citations.get("worksWithCitation") == 0, empty_citations.get("worksWithCitation"))
        check("无数据时篇均字段缺席而不是 0",
              "averagePerWork" not in empty_citations, empty_citations.get("averagePerWork"))
        check("无数据时给出「无法计算」的说明",
              "无法计算" in (empty_citations.get("averageNote") or ""),
              empty_citations.get("averageNote"))
        check("非论文成果仍被单列",
              int(empty_citations.get("worksNotCitable") or 0) > 0,
              empty_citations.get("worksNotCitable"))
    else:
        print("  SKIP  库中没有「有成果但无引用量」的学者样本")

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total_checks = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total_checks, passed, total_checks - passed))
    return 0 if passed == total_checks else 1


if __name__ == "__main__":
    sys.exit(main())
