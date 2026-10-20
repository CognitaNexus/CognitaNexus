#!/usr/bin/env python3
"""把「任务已暂停/已结束，但批次还挂在 PENDING、暂存记录仍是 STAGED」的积压推掉。

为什么需要这个脚本：交接重试只发生在任务真的跑一轮的时候（CollectExecutionService.runOnce
先 drainPendingHandoffs 再抓新页），而 runOnce 对 PAUSED 与终态任务直接跳过。于是暂停
前那一刻没交付成功的批次就永远没人再碰——记录停在 STAGED、批次停在 PENDING，
两个状态都不会自己变化，也不会报错。线上实测：job_79bce2 c1289e（OpenAlex）在 401
那轮里交了 PENDING 批次，随后被暂停，50 条已抓到的记录卡了三天。

做法：临时恢复任务 → 跑一轮（这一轮先交付遗留批次，再抓一页）→ 恢复原状态。
跑一轮会顺带抓一页，这正是想要的：说明来源确实恢复了。若来源仍不可用，
这一轮会在交付完遗留批次之后才失败，遗留批次已经不在 PENDING 了。

用法（在目标机上，接口只监听回环）：
    CN_ADMIN_PASSWORD=... python3 flush-handoff-backlog.py --job job_xxx
    CN_ADMIN_PASSWORD=... python3 flush-handoff-backlog.py --job job_xxx --keep-running
    CN_ADMIN_PASSWORD=... python3 flush-handoff-backlog.py --job job_xxx --dry-run

口令走环境变量而不是命令行参数：命令行参数会出现在 ps 输出里。
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_CORE = "http://127.0.0.1:8081"
DEFAULT_INGESTION = "http://127.0.0.1:8082"


class Session:
    """core 会话 + 双提交 CSRF。ingestion 的管理端点由 AdminAuthFilter 委托 core 校验。"""

    def __init__(self, core: str, username: str, password: str) -> None:
        self.core = core
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )
        self.username = username
        self._login(password)

    def csrf(self) -> str:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return ""

    def _login(self, password: str) -> None:
        # 先取 CSRF Cookie：登录本身是状态变更请求，缺这个头会被 403 拒掉
        self.opener.open(self.core + "/api/v1/auth/csrf", timeout=20).read()
        body = json.dumps({"username": self.username, "password": password}).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        token = self.csrf()
        if token:
            headers["X-XSRF-TOKEN"] = token
        req = urllib.request.Request(
            self.core + "/api/v1/auth/login", data=body, headers=headers, method="POST"
        )
        try:
            with self.opener.open(req, timeout=20) as resp:
                payload = json.load(resp)
        except urllib.error.HTTPError as exc:
            raise SystemExit(f"登录失败 HTTP {exc.code}：{exc.read()[:200].decode(errors='replace')}")
        roles = ((payload or {}).get("data") or {}).get("roles") or []
        if "PLATFORM_ADMIN" not in roles:
            raise SystemExit(f"该账号没有平台管理员角色，实际角色：{roles}")

    def get(self, url: str) -> dict:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with self.opener.open(req, timeout=30) as resp:
            return json.loads(resp.read())

    def post(self, url: str, payload: dict | None = None) -> dict:
        data = json.dumps(payload if payload is not None else {}).encode()
        req = urllib.request.Request(
            url,
            data=data,
            headers={
                "Content-Type": "application/json",
                "X-XSRF-TOKEN": self.csrf(),
                "Accept": "application/json",
            },
            method="POST",
        )
        with self.opener.open(req, timeout=180) as resp:
            return json.loads(resp.read())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--job", required=True, help="任务 id，例如 job_79bce2c1289e651ee819088996")
    ap.add_argument("--core", default=DEFAULT_CORE)
    ap.add_argument("--ingestion", default=DEFAULT_INGESTION)
    ap.add_argument("--username", default=os.environ.get("CN_ADMIN_USERNAME", "cnadmin"))
    ap.add_argument("--keep-running", action="store_true", help="跑完不恢复暂停状态")
    ap.add_argument("--dry-run", action="store_true", help="只看当前状态，不改")
    args = ap.parse_args()

    password = os.environ.get("CN_ADMIN_PASSWORD")
    if not password:
        print("缺少 CN_ADMIN_PASSWORD 环境变量", file=sys.stderr)
        return 2

    sess = Session(args.core, args.username, password)
    base = args.ingestion + "/api/v1/admin/ingestion/jobs/" + args.job

    before = sess.get(base)["data"]
    print(f"任务当前状态：{before.get('status')}  已交付={before.get('totalHandedOff')}")

    batches = sess.get(base + "/batches")["data"]
    items = batches.get("items", batches if isinstance(batches, list) else [])
    pending = [b for b in items if str(b.get("status")) == "PENDING"]
    print(f"批次：共 {len(items)} 个，其中 PENDING {len(pending)} 个")
    for b in pending:
        print(f"  - {b.get('id')} 条数={b.get('recordCount')} 已尝试={b.get('attempts')} 下次={b.get('nextAttemptAt')}")

    if args.dry_run:
        return 0

    original = str(before.get("status"))
    if original != "PAUSED":
        print(f"原状态是 {original}，不是 PAUSED；本脚本只处理暂停任务", file=sys.stderr)
        return 3

    print("恢复任务…")
    print("  ", sess.post(base + "/resume", {"reason": "补交接遗留批次"})["data"].get("status"))

    print("跑一轮（先交付遗留批次，再抓一页）…")
    outcome = sess.post(base + "/run?maxRounds=1", {})["data"]
    print("  ", json.dumps(outcome, ensure_ascii=False))

    after = sess.get(base)["data"]
    print(f"跑完：状态={after.get('status')} 已交付={after.get('totalHandedOff')} 消息={after.get('lastMessage')}")

    if not args.keep_running:
        print("恢复暂停状态…")
        print("  ", sess.post(base + "/pause", {"reason": "补交接完成，回到暂停"})["data"].get("status"))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
