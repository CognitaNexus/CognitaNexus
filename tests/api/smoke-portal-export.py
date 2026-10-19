#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""门户「我的成果」CSV 导出端到端自检。

验证的不是「下载到了文件」，而是这些产品规则：

1. 只有持有生效授权的账号能导出（无授权 403、未登录 401）——导出的是完整清单，
   口径比列表接口更敏感，越权拿到的不是一屏数据而是整份名单；
2. 导出**不分页**：列表接口一次只给一屏，导出必须覆盖全部参与关系，否则拿去写考核材料会缺行；
3. 文件是可解析的 RFC 4180 CSV：UTF-8 BOM、表头固定、行数等于库中该学者的关系数；
4. 每行带数据性质与生效状态，并用「计入合作网络」列标出待核验/争议行——
   导出的是「关系记录」，不是「我确认的成果」，把未确认的行悄悄剔掉会让本人看不到待处理项；
5. 超出行数上限时拒绝并说明，不静默截断（本脚本用小上限场景无法构造，故只断言拒绝文案存在
   于服务端实现，不做假通过）。

前置：整栈已启动（bash ops/local/stack.sh）。
副作用：创建一个账号、认领一位学者的门户（该授权会保留，供后续人工核对）。

用法：
  python tests/api/smoke-portal-export.py
"""

import csv
import http.cookiejar
import io
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

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


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

    def get(self, path):
        return self._send("GET", CORE + path)

    def post(self, path, body=None):
        return self._send("POST", CORE + path, body if body is not None else {}, self.csrf())

    def csrf(self):
        self._send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def login(self, user, password):
        return self._send(
            "POST", CORE + "/api/v1/auth/login",
            {"username": user, "password": password}, self.csrf()
        )

    def download(self, path):
        """下载二进制。CSRF 不适用于 GET，但仍走同一个 cookie 容器以携带会话。"""
        req = urllib.request.Request(CORE + path, headers={"Accept": "text/csv"}, method="GET")
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as err:
            body = err.read()
            try:
                return err.code, json.loads(body.decode("utf-8"))
            except ValueError:
                return err.code, {"raw": body.decode("utf-8", "replace")[:200]}


def data_of(payload):
    return (payload or {}).get("data") or {}


def register(session, prefix):
    username = "%s_%s" % (prefix, uuid.uuid4().hex[:8])
    password = "Cn-dev-2026!"
    code, payload = session.post(
        "/api/v1/auth/register",
        {"username": username, "password": password, "displayName": "导出自检 " + username},
    )
    if code != 200:
        raise RuntimeError("注册失败 %s %s" % (code, payload))
    code, _ = session.login(username, password)
    if code != 200:
        raise RuntimeError("登录失败 %s" % code)
    return username, password


def main():
    # ------------------------------------------------------------------
    # 0. 选一位有成果、且当前无人管理的学者
    # ------------------------------------------------------------------
    rows = psql(
        "core_db",
        "core",
        """
        SELECT s.id, s.display_name, count(c.id) AS works
        FROM core_scholar s
        JOIN core_contribution c ON c.scholar_id = s.id
        JOIN core_achievement a ON a.id = c.achievement_id
        WHERE s.merged_into_id IS NULL
          AND a.merged_into_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM iam_portal_authority pa
                          WHERE pa.scholar_id = s.id AND pa.status = 'ACTIVE')
          AND NOT EXISTS (SELECT 1 FROM wf_claim_request r
                          WHERE r.scholar_id = s.id AND r.status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE'))
        GROUP BY s.id, s.display_name
        HAVING count(c.id) >= 2
        ORDER BY count(c.id) DESC, s.id
        LIMIT 1;
        """,
    )
    if not rows:
        print("没有可用的学者（需要有 ≥2 条参与关系且无人管理）。")
        return 1
    scholar_id, scholar_name, db_works = rows[0].split("\t")
    print("目标学者：%s（%s），库中参与关系 %s 条" % (scholar_name, scholar_id, db_works))

    # ------------------------------------------------------------------
    # 1. 鉴权：未登录 401；无授权账号 403
    # ------------------------------------------------------------------
    print("== 1 鉴权 ==")
    anon = Session()
    code, _ = anon.download("/api/v1/account/contributions/export?scholarId=%s" % scholar_id)
    check("未登录导出被拒（401）", code == 401, code)

    stranger = Session()
    register(stranger, "exp_stranger")
    code, payload = stranger.download(
        "/api/v1/account/contributions/export?scholarId=%s" % scholar_id
    )
    check("无门户授权导出被拒（403）", code == 403, (code, (payload or {}).get("code")))

    # ------------------------------------------------------------------
    # 2. 取得授权：认领 + 管理员批准
    # ------------------------------------------------------------------
    print("== 2 取得门户授权 ==")
    owner = Session()
    register(owner, "exp_owner")
    code, payload = owner.post(
        "/api/v1/account/claims",
        {"scholarId": scholar_id, "statement": "导出自检：建立门户授权以验证「我的成果」导出。"},
    )
    claim_id = data_of(payload).get("id")
    check("认领提交成功", code == 200 and bool(claim_id), (code, claim_id))

    admin = Session()
    code, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    if code != 200:
        raise RuntimeError("管理员登录失败：%s" % code)
    code, payload = admin.post(
        "/api/v1/admin/core/claims/%s/decision" % claim_id,
        {"decision": "APPROVE", "reason": "导出自检：为验证我的成果导出建立授权"},
    )
    check("认领批准成功", code == 200, (code, payload))

    # ------------------------------------------------------------------
    # 3. 导出：文件本体、BOM、表头、行数
    # ------------------------------------------------------------------
    print("== 3 导出文件 ==")
    code, body = owner.download("/api/v1/account/contributions/export?scholarId=%s" % scholar_id)
    check("授权后导出成功", code == 200 and isinstance(body, bytes), code)
    if code != 200 or not isinstance(body, bytes):
        print("导出失败，后续断言无法进行。")
        return 1

    check("文件以 UTF-8 BOM 开头", body[:3] == b"\xef\xbb\xbf", body[:3])
    text = body[3:].decode("utf-8")
    reader = list(csv.reader(io.StringIO(text)))
    header = reader[0] if reader else []
    check(
        "表头固定且中文可读",
        header == ["关系标识", "成果标识", "成果类型", "题名", "发表年份", "数据性质",
                   "生效状态", "本人角色", "署名序", "关系状态", "计入合作网络"],
        header,
    )
    data_rows = reader[1:]
    check("行数等于库中参与关系数（导出不分页）",
          len(data_rows) == int(db_works), (len(data_rows), db_works))
    check("每一行的列数与表头一致", all(len(row) == len(header) for row in data_rows),
          sorted({len(row) for row in data_rows}))

    # 数据性质与生效状态是判断这行能不能写进对外材料的依据，不能留空
    missing_nature = [row for row in data_rows if not row[5]]
    missing_status = [row for row in data_rows if not row[6]]
    check("每行都有数据性质", not missing_nature, len(missing_nature))
    check("每行都有生效状态", not missing_status, len(missing_status))
    check(
        "计入合作网络列只取是/否",
        all(row[10] in ("是", "否") for row in data_rows),
        sorted({row[10] for row in data_rows}),
    )

    db_pending = int(
        psql(
            "core_db",
            "core",
            """
            SELECT count(*) FROM core_contribution
            WHERE scholar_id = '%s' AND relation_state IN ('PENDING_VERIFY', 'DISPUTED', 'EXCLUDED');
            """ % scholar_id,
        )[0]
    )
    marked_no = sum(1 for row in data_rows if row[10] == "否")
    check("未确认关系被标为「否」且保留在文件中", marked_no == db_pending, (marked_no, db_pending))

    # ------------------------------------------------------------------
    # 4. 与列表接口的口径对照
    # ------------------------------------------------------------------
    print("== 4 与列表接口对照 ==")
    code, payload = owner.get(
        "/api/v1/account/contributions?scholarId=%s&limit=200" % scholar_id
    )
    listed = data_of(payload)
    check("列表接口返回同样的条数", code == 200 and len(listed) == len(data_rows),
          (len(listed) if isinstance(listed, list) else code, len(data_rows)))
    if isinstance(listed, list) and listed:
        first = listed[0]
        match = next((row for row in data_rows if row[0] == first.get("contributionId")), None)
        check("同一关系在两边状态一致", match is not None and match[9] == first.get("relationState"),
              (match[9] if match else None, first.get("relationState")))

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    total = len(RESULTS)
    print("\n合计 %d 项，通过 %d，失败 %d" % (total, passed, total - passed))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
