#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""注销冷静期「到期自动执行」的端到端验收。

验证的是一条承诺：账号设置页告诉用户「7 天后生效」，那就必须真的在到期后生效，
而不是「7 天后等管理员点一下」。

为什么必须真跑：单测只能证明 executeDue 调了 disable；这条链路上还有三件事只有真库真服务
才能证明——SQL 的 now() >= effective_at 条件真的放行、停用后原会话与登录真的失效、
审计主体真的写成了 SYSTEM 而不是伪造一个管理员账号。

做法：注册临时账号 → 申请注销 → 用 psql 把 effective_at 改到过去（模拟 7 天已过）→
调用内部维护端点触发扫描（与定时任务同一段逻辑）→ 逐项断言库与接口状态。

用法（前置：整栈已启动，主库可达）：
  python tests/api/verify-closure-sweep.py

副作用：创建一个 clo_auto_* 测试账号并最终将其停用；账号不清理——它是审计记录的 actor。
"""

import http.cookiejar
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL_BIN", "D:/dev-tools/pgsql/bin/psql.exe")
# 内部端点用 query 服务身份调用：core 的令牌表里只有 ingestion / query 两个主体。
SERVICE_NAME = os.environ.get("CN_INTERNAL_SERVICE_NAME", "query")
SERVICE_TOKEN = os.environ.get("CN_QUERY_SERVICE_TOKEN", "cn_query_service_dev")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def psql(sql):
    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    env["MSYS_NO_PATHCONV"] = "1"
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db", "-At", "-f", "-"],
        input=sql.encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode("utf-8", "replace")[:400])
    return [line.strip() for line in out.stdout.decode("utf-8", "replace").splitlines() if line.strip()]


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def _send(self, method, url, body=None, token=None):
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["X-XSRF-TOKEN"] = token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as err:
            payload = err.read()
            try:
                return err.code, json.loads(payload.decode("utf-8")) if payload else {}
            except ValueError:
                return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}

    def csrf(self):
        self._send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def login(self, user, password):
        return self._send(
            "POST", CORE + "/api/v1/auth/login", {"username": user, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def register(session, prefix):
    username = "%s_%s" % (prefix, uuid.uuid4().hex[:8])
    password = "Cn-dev-2026!"
    code, payload = session.post(
        "/api/v1/auth/register",
        {"username": username, "password": password, "displayName": "冷静期自检 " + username},
    )
    if code != 200:
        raise RuntimeError("注册失败 %s %s" % (code, payload))
    code, _ = session.login(username, password)
    if code != 200:
        raise RuntimeError("登录失败 %s" % code)
    return username, password


def sweep(limit=50, service=SERVICE_NAME, token=SERVICE_TOKEN):
    """触发一次冷静期扫描。与定时任务调用同一个 executeDue，不存在第二套逻辑。"""
    req = urllib.request.Request(
        CORE + "/internal/v1/maintenance/closure-sweep?limit=%d" % limit,
        data=b"",
        headers={
            "X-Service-Name": service,
            "X-Service-Token": token,
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as err:
        payload = err.read()
        try:
            return err.code, json.loads(payload.decode("utf-8")) if payload else {}
        except ValueError:
            return err.code, {"raw": payload.decode("utf-8", "replace")[:200]}


def main():
    print("== 注销冷静期：到期自动执行 ==")

    applicant = Session()
    username, password = register(applicant, "clo_auto")

    code, payload = applicant.post(
        "/api/v1/account/closure", {"reason": "验收冷静期到期自动执行"}
    )
    closure = data_of(payload)
    request_id = closure.get("id")
    check("申请注销成功", code in (200, 201) and bool(request_id), (code, request_id))
    if not request_id:
        return finish()

    # 内部端点不能匿名可调用：否则任何人都能触发停用动作。
    code, _ = sweep(service="attacker", token="wrong")
    check("伪造服务身份被拒", code == 403, code)

    # 冷静期未到时先触发一次：必须不执行（SQL 条件挡住的不是应用层判断）。
    # 只断言本条的状态，不断言全局 executed==0——库里可能还有别的到期申请，那是正常执行。
    code, payload = sweep()
    status_before = psql(
        "SELECT status FROM core_account_closure_request WHERE id = '%s';" % request_id
    )
    check("未到期时扫描不执行", code == 200 and status_before == ["PENDING"], (code, status_before))
    account_before = psql("SELECT status FROM iam_account WHERE username = '%s';" % username)
    check("未到期时账号仍可用", account_before == ["ACTIVE"], account_before)

    # 模拟「7 天已过」。改的是数据，不是应用层开关。
    psql(
        "UPDATE core_account_closure_request SET effective_at = now() - interval '1 minute'"
        " WHERE id = '%s';" % request_id
    )

    code, payload = sweep()
    executed = data_of(payload).get("executed")
    check("到期后扫描执行", code == 200 and executed and executed >= 1, (code, executed))

    row = psql(
        "SELECT status, coalesce(decided_by, '<null>'), coalesce(decision_note, '<null>')"
        " FROM core_account_closure_request WHERE id = '%s';" % request_id
    )
    fields = row[0].split("|") if row else []
    check("申请状态转为 APPROVED", fields[:1] == ["APPROVED"], fields[:1])
    # decided_by 为空是刻意设计：没有人类裁决人时不伪造管理员；审计主体记为 SYSTEM。
    check("裁决人为空（不伪造管理员）", len(fields) > 1 and fields[1] == "<null>", fields[1:2])
    check("裁决说明标注系统任务", len(fields) > 2 and "系统" in fields[2], fields[2:3])

    account_status = psql("SELECT status FROM iam_account WHERE username = '%s';" % username)
    check("账号已停用", account_status == ["DISABLED"], account_status)

    audit = psql(
        "SELECT coalesce(actor_username, '<null>') FROM aud_audit_event"
        " WHERE action = 'ACCOUNT_CLOSURE_APPROVED' AND target_id ="
        " (SELECT account_id FROM core_account_closure_request WHERE id = '%s')"
        " ORDER BY occurred_at DESC LIMIT 1;" % request_id
    )
    check("审计主体是 SYSTEM", audit == ["SYSTEM"], audit)

    # 停用后原凭据不能再用：只改状态而不提升会话版本时，旧 Cookie 仍然有效。
    relogin = Session()
    code, payload = relogin.login(username, password)
    check("停用后无法重新登录", code in (401, 403), (code, data_of(payload).get("message")))
    code, _ = applicant.get("/api/v1/account/export")
    check("原会话已失效", code in (401, 403), code)

    # 幂等：再扫一次不能重复执行。同样只认本条：decided_at 不该被二次改写。
    decided_before = psql(
        "SELECT decided_at FROM core_account_closure_request WHERE id = '%s';" % request_id
    )
    code, payload = sweep()
    decided_after = psql(
        "SELECT decided_at FROM core_account_closure_request WHERE id = '%s';" % request_id
    )
    check(
        "重复扫描不重复执行",
        code == 200 and decided_before == decided_after and bool(decided_before),
        (code, decided_before, decided_after),
    )

    return finish()


def finish():
    total = len(RESULTS)
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n共 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
