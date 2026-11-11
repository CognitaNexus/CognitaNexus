#!/usr/bin/env python3
"""把引用网络补满：反复触发 ingestion 的补水接口，直到没有待补成果或额度不足。

为什么要在服务器上跑循环，而不是一次调大 limit：一次补水是同步等待上游的，
HydrationService 内部按 batchSize=25 分批、每批一次上游请求，补 2.4 万篇要近千次
请求、几十分钟。单次 HTTP 请求扛不住这个时长（nginx proxy_read_timeout 120s），
所以按 maxWorksPerRun 分片，每片一次调用，由本脚本串起来。

为什么强制 provider=OPENALEX：默认的 AUTO 在剩余额度低于阈值时整轮改用 Crossref，
而 Crossref 是逐篇请求、没有批量。补同一批量，Crossref 要几百倍的时间。这里宁可
停下来等下一天，也不要悄悄换成慢几十倍的路径。

为什么要登录：ingestion 的管理端点由 AdminAuthFilter 委托 core 校验会话、能力与
CSRF，匿名调用一律 401。脚本先向 core 登录拿 SESSION + XSRF-TOKEN，之后每个请求
同时带 Cookie 和提交式 CSRF 头。

用法（在目标机上，接口只监听回环）：
    CN_ADMIN_PASSWORD=... python3 backfill-citations.py
    CN_ADMIN_PASSWORD=... python3 backfill-citations.py --limit 500
    CN_ADMIN_PASSWORD=... python3 backfill-citations.py --dry-run   # 只跑一片看回执

口令走环境变量而不是命令行参数：命令行参数会出现在 ps 输出里，同机其他用户可见。
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import urllib.error
import urllib.request

DEFAULT_CORE = "http://127.0.0.1:8081"
DEFAULT_INGESTION = "http://127.0.0.1:8082"
HYDRATE_PATH = "/api/v1/admin/ingestion/citations/hydrate"

# 额度低于这个值就停：单位是美分（服务端已把 x-ratelimit-remaining-usd 折算过）。
# 补满全站引用实测只需约 10 美分，所以这个阈值足够保守。
MIN_CREDITS_CENTS = 5


class Session:
    """core 会话 + 双提交 CSRF。"""

    def __init__(self, core: str, username: str, password: str) -> None:
        self.core = core
        self.username = username
        self.password = password
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def csrf(self) -> str:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return ""

    def login(self) -> None:
        # 先取 CSRF Cookie：登录本身是状态变更请求，缺这个头会被拒绝
        self.opener.open(self.core + "/api/v1/auth/csrf", timeout=20).read()
        body = json.dumps({"username": self.username, "password": self.password}).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        token = self.csrf()
        if token:
            headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.core + "/api/v1/auth/login", data=body, headers=headers, method="POST"
        )
        response = self.opener.open(request, timeout=30)
        payload = json.load(response)
        roles = (payload.get("data") or {}).get("roles") or []
        if "PLATFORM_ADMIN" not in roles:
            raise RuntimeError(f"该账号没有平台管理员角色，实际角色：{roles}")

    def post(self, url: str, timeout: int) -> dict:
        headers = {"Accept": "application/json"}
        token = self.csrf()
        if token:
            headers["X-XSRF-TOKEN"] = token
        # 空 body 才是 POST；不带 data 的话 urllib 会发 GET，接口返回 405
        request = urllib.request.Request(url, data=b"", headers=headers, method="POST")
        with self.opener.open(request, timeout=timeout) as response:
            return json.load(response)


def hydrate(session: Session, base: str, limit: int, provider: str, timeout: int) -> dict:
    url = f"{base}{HYDRATE_PATH}?limit={limit}&provider={provider}"
    payload = session.post(url, timeout)
    if "data" not in payload:
        raise RuntimeError(f"回执结构不符合预期：{str(payload)[:200]}")
    return payload["data"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", default=DEFAULT_CORE)
    parser.add_argument("--base", default=DEFAULT_INGESTION)
    parser.add_argument("--username", default=os.environ.get("CN_ADMIN_USERNAME", "cnadmin"))
    parser.add_argument("--limit", type=int, default=1000, help="每片目标篇数")
    parser.add_argument("--provider", default="OPENALEX")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    password = os.environ.get("CN_ADMIN_PASSWORD")
    if not password:
        print("缺少 CN_ADMIN_PASSWORD 环境变量", flush=True)
        return 2

    session = Session(args.core, args.username, password)
    session.login()
    print(f"已登录 {args.username}（具备 PLATFORM_ADMIN）", flush=True)

    total_works = 0
    total_refs = 0
    total_edges = 0
    rounds = 0
    idle = 0

    while True:
        rounds += 1
        try:
            report = hydrate(session, args.base, args.limit, args.provider, args.timeout)
        except urllib.error.HTTPError as ex:
            body = ex.read().decode(errors="replace")[:300]
            if ex.code == 401:
                # 会话过期：重登一次再继续，不把一次过期当成本轮结束
                print(f"[{rounds}] 会话已过期，重新登录", flush=True)
                session.login()
                rounds -= 1
                continue
            print(f"[{rounds}] HTTP {ex.code}：{body}", flush=True)
            return 1
        except Exception as ex:  # noqa: BLE001 - 运维脚本，任何异常都要留痕而不是栈崩
            print(f"[{rounds}] 调用失败：{type(ex).__name__} {ex}", flush=True)
            return 1

        works = report.get("worksFetched") or 0
        refs = report.get("referencesWritten") or 0
        edges = report.get("resolvedEdges") or 0
        credits = report.get("creditsRemaining")
        stop = report.get("stoppedReason") or ""
        total_works += works
        total_refs += refs
        total_edges += edges

        print(
            f"[{rounds}] 取到 {works} 篇 / 写入 {refs} 条引用 / 解析成边 {edges} / "
            f"剩余额度 {credits} 美分 / 停止原因：{stop}",
            flush=True,
        )
        print(f"    累计：{total_works} 篇，{total_refs} 条引用，{total_edges} 条边", flush=True)

        if args.dry_run:
            return 0

        if stop.startswith("没有更多待补成果"):
            print("补完了：没有更多待补成果", flush=True)
            break

        if credits is not None and credits < MIN_CREDITS_CENTS:
            print(f"额度剩 {credits} 美分，低于阈值 {MIN_CREDITS_CENTS}，停止", flush=True)
            break

        if works == 0:
            # 连续空转说明剩下的目标都取不到（DOI 归一不匹配、上游没有这篇等）。
            # 不设退出条件的话脚本会整夜空转。
            idle += 1
            if idle >= 3:
                print("连续 3 片没有任何成果，停止（剩下的目标多半在上游查不到）", flush=True)
                break
        else:
            idle = 0

    print(
        f"结束：共 {rounds} 片，{total_works} 篇，{total_refs} 条引用，{total_edges} 条可解析边",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
