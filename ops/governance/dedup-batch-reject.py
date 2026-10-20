#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按规则批量驳回去重候选，让被「存在相似候选」挡住的成果重新走发布评估。

背景：采集进来的成果在 core 落库后要过 AutoPublishPolicy 五条判据，其中一条是「不存在相似
标题候选」。候选生成阈值只有 0.55，同一个学科的词面重叠就够进候选，于是治理工作台堆了
两万多条候选，九千多条成果卡在 PENDING_GOVERNANCE 出不去。

抽证结果（2026-10-20 对线上 OPEN 候选随机取样）：
  - 相似度 < 0.70 的 6 对，肉眼 6 对全是不同论文（钙钛矿电池、图神经网络同主题不同文）；
  - 相似度 >= 0.90 的 5 对，3 对真重复、1 对存疑、1 对假阳性（同系列奖项不同年份）。

所以本脚本只做「驳回」，不做合并：驳回错了可以再合并，合并错了要把关系、来源归属与门户
授权拆回来，代价不对称（见 GovernanceController 注释）。默认规则也刻意保守——相似度低于
阈值、且两端出版年份都已知且不同。年份缺失或相同的留在工作台给人看。

调用的是 /api/v1/admin/core/governance/candidates/batch-reject，因此：
  - 审计事件由服务端按管理员身份记录，动作是 DEDUP_CANDIDATE_REJECTED；
  - 候选裁决后服务端会自行重评估发布，审计动作是 ACHIEVEMENT_RELEASE_RECHECKED，
    与人工「确认发布」分得开，事后能看出是谁放行的；
  - 单批上限 200 条（服务端 MAX_BATCH_RESOLVE），超出会被拒。

用法（在服务器上跑，psql 走 peer 认证）：
  CN_GOV_ADMIN_PASSWORD=... python3 ops/governance/dedup-batch-reject.py --dry-run
  CN_GOV_ADMIN_PASSWORD=... python3 ops/governance/dedup-batch-reject.py --execute --limit 600

不带 --execute 时只统计不写库。
"""

import argparse
import http.cookiejar
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

CORE_DB = "core_db"
DEFAULT_BASE = "http://127.0.0.1:8081"
DEFAULT_REASON = (
    "规则批量驳回：标题相似度低于阈值且两端出版年份均已知且不同，判为不同成果；"
    "依据 ops/governance/dedup-batch-reject.py 抽样核验"
)


def psql(sql):
    proc = subprocess.run(
        ["sudo", "-u", "postgres", "psql", "-d", CORE_DB, "-At", "-F", "\t"],
        input=sql.encode("utf-8"),
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.exit("psql 执行失败：" + proc.stderr.decode("utf-8", "replace")[:500])
    return [line for line in proc.stdout.decode("utf-8").splitlines() if line.strip()]


def bucket_counts(max_similarity):
    rows = psql(
        """
        select case when (c.evidence->>'similarity')::numeric >= 0.9 then 'A_ge_0.9'
                    when (c.evidence->>'similarity')::numeric >= 0.7 then 'B_0.7_0.9'
                    else 'C_lt_0.7' end as bucket,
               case when l.publication_year is null or r.publication_year is null then 'year_unknown'
                    when l.publication_year = r.publication_year then 'year_same'
                    else 'year_diff' end as years,
               count(*)
        from gov_dedup_candidate c
        join core_achievement l on l.id = c.left_achievement_id
        join core_achievement r on r.id = c.right_achievement_id
        where c.status = 'OPEN'
        group by 1, 2 order by 1, 2;
        """
    )
    return rows


def select_candidate_ids(max_similarity, limit):
    clause = f"""
        select c.id
        from gov_dedup_candidate c
        join core_achievement l on l.id = c.left_achievement_id
        join core_achievement r on r.id = c.right_achievement_id
        where c.status = 'OPEN'
          and (c.evidence->>'similarity')::numeric < {max_similarity}
          and l.publication_year is not null
          and r.publication_year is not null
          and l.publication_year <> r.publication_year
        order by c.created_at
    """
    if limit and limit > 0:
        clause += f" limit {limit}"
    return psql(clause + ";")


def select_samples(max_similarity, n):
    return psql(
        f"""
        select round((c.evidence->>'similarity')::numeric, 3)::text
               || ' y' || coalesce(l.publication_year::text, '?')
               || '/' || coalesce(r.publication_year::text, '?'),
               left(l.title, 46), left(r.title, 46)
        from gov_dedup_candidate c
        join core_achievement l on l.id = c.left_achievement_id
        join core_achievement r on r.id = c.right_achievement_id
        where c.status = 'OPEN'
          and (c.evidence->>'similarity')::numeric < {max_similarity}
          and l.publication_year is not null
          and r.publication_year is not null
          and l.publication_year <> r.publication_year
        order by random() limit {n};
        """
    )


class Session:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.ProxyHandler({}),
        )

    def csrf(self):
        try:
            self.opener.open(self.base + "/api/v1/auth/csrf", timeout=15).read()
        except urllib.error.HTTPError:
            pass
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return ""

    def call(self, method, path, body=None):
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        token = self.csrf()
        if token:
            headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.base + path, data=data, headers=headers, method=method
        )
        try:
            response = self.opener.open(request, timeout=120)
            return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")[:500]

    def login(self, username, password):
        self.csrf()
        status, payload = self.call(
            "POST", "/api/v1/auth/login", {"username": username, "password": password}
        )
        if status != 200:
            sys.exit(f"登录失败 {status}：{payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)[:300]}")
        return payload


def main():
    parser = argparse.ArgumentParser(description="按规则批量驳回去重候选并触发发布重评估")
    parser.add_argument("--base", default=os.environ.get("CORE_BASE", DEFAULT_BASE))
    parser.add_argument("--user", default=os.environ.get("CN_GOV_ADMIN_USER", "cnadmin"))
    parser.add_argument("--password", default=os.environ.get("CN_GOV_ADMIN_PASSWORD", ""))
    parser.add_argument("--max-similarity", type=float, default=0.70)
    parser.add_argument("--batch", type=int, default=200)
    parser.add_argument("--limit", type=int, default=0, help="本次最多处理多少条候选，0 表示不限")
    parser.add_argument("--sample", type=int, default=0, help="打印若干随机样本供人工核验")
    parser.add_argument("--execute", action="store_true", help="真正写库；不加此参数只统计")
    parser.add_argument("--dry-run", action="store_true", help="只统计不写库（默认行为，显式写在命令里便于复核）")
    parser.add_argument("--reason", default=DEFAULT_REASON)
    args = parser.parse_args()

    print("=== 候选分档 ===")
    for row in bucket_counts(args.max_similarity):
        print("  " + row.replace("\t", " | "))

    ids = select_candidate_ids(args.max_similarity, args.limit)
    print(f"\n规则命中候选：{len(ids)} 条（相似度 < {args.max_similarity} 且年份均已知且不同）")

    if args.sample > 0:
        print("\n=== 随机样本 ===")
        for row in select_samples(args.max_similarity, args.sample):
            print("  " + row.replace("\t", " | "))

    affected = psql(
        f"""
        with rej as (
          select c.id from gov_dedup_candidate c
          join core_achievement l on l.id = c.left_achievement_id
          join core_achievement r on r.id = c.right_achievement_id
          where c.status = 'OPEN'
            and (c.evidence->>'similarity')::numeric < {args.max_similarity}
            and l.publication_year is not null and r.publication_year is not null
            and l.publication_year <> r.publication_year
        ), still as (
          select left_achievement_id as id from gov_dedup_candidate
           where status = 'OPEN' and id not in (select id from rej)
          union
          select right_achievement_id from gov_dedup_candidate
           where status = 'OPEN' and id not in (select id from rej)
        )
        select count(*) from core_achievement a
        where a.effective_status = 'PENDING_GOVERNANCE' and a.data_nature = 'REAL'
          and exists (select 1 from core_identifier_registry x
                       where x.entity_type = 'ACHIEVEMENT' and x.entity_id = a.id)
          and a.id not in (select id from still);
        """
    )
    print(f"清完后预计可自动放行成果：{affected[0] if affected else '?'} 条")

    if args.dry_run or not args.execute:
        print("\n未加 --execute，仅统计，未写库。")
        return 0

    if not ids:
        print("没有命中候选，退出。")
        return 0
    if not args.password:
        sys.exit("缺少管理员口令：用 CN_GOV_ADMIN_PASSWORD 环境变量或 --password 传入")

    session = Session(args.base)
    session.login(args.user, args.password)
    print(f"\n已登录 {args.user}，开始分批驳回（每批 {args.batch} 条）")

    total_resolved = 0
    total_released = 0
    skipped = []
    for start in range(0, len(ids), args.batch):
        chunk = ids[start : start + args.batch]
        status, payload = session.call(
            "POST",
            "/api/v1/admin/core/governance/candidates/batch-reject",
            {"candidateIds": chunk, "reason": args.reason},
        )
        if status != 200:
            print(f"  第 {start // args.batch + 1} 批失败 {status}：{payload}")
            break
        data = payload.get("data", {}) if isinstance(payload, dict) else {}
        resolved = int(data.get("resolved") or 0)
        released = int(data.get("releasedCount") or 0)
        total_resolved += resolved
        total_released += released
        skipped.extend(data.get("skipped") or [])
        print(
            f"  第 {start // args.batch + 1} 批：请求 {len(chunk)}，裁决 {resolved}，"
            f"放行 {released}，累计放行 {total_released}"
        )
        time.sleep(0.3)

    print(f"\n完成：裁决候选 {total_resolved} 条，放行成果 {total_released} 条，跳过 {len(skipped)} 条")
    if skipped:
        print("跳过明细（前 10 条）：")
        for item in skipped[:10]:
            print("  - " + str(item))
    return 0


if __name__ == "__main__":
    sys.exit(main())
