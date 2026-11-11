#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""账号口令自助与管理员重置的端到端自检。

平台不接邮件、不接短信，所以「忘记口令」只有一条路：管理员重置为一次性口令，本人登录后改掉。
这条路上每一步都可能只做一半，而这个脚本把整条链路按真实顺序走一遍：

1. 管理员重置 `authz-probe` 的口令 → 拿到一次性口令（只出现这一次）；
2. 该账号用一次性口令登录：必须成功，且响应里的 mustChangePassword 为 true；
3. 用一次性口令改密 → 成功后，**旧的一次性口令必须立刻失效**；
4. 新口令登录 → mustChangePassword 变回 false；
5. 最后把口令改回固定值，保证脚本可重复跑（否则第二次跑就没有「旧口令」可用了）。

同时验证拒绝路径：
- 未登录调用改密 → 401；
- 普通用户重置他人 → 403，且不产生任何一次性口令；
- 管理员重置自己 → 422，提示应走本人改密。

前置：整栈已启动；`authz-probe` 由 smoke-authz-matrix.py 创建或已存在。
"""

import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PROBE_USER = "authz-probe"
PROBE_PASS = "AuthzProbe2026x"
TEMP_PASS = "ChangedByProbe2026y"

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def send(self, method, path_or_url, body=None, token=None):
        url = path_or_url if path_or_url.startswith("http") else CORE + path_or_url
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=30) as response:
                text = response.read().decode("utf-8", "replace")
                return response.status, json.loads(text) if text else {}
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            try:
                return error.code, json.loads(text) if text else {}
            except ValueError:
                return error.code, {"raw": text}

    def csrf(self):
        self.send("GET", "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def post(self, path, body=None):
        return self.send("POST", path, body if body is not None else {}, self.csrf())

    def login(self, username, password):
        self.csrf()
        return self.send(
            "POST", "/api/v1/auth/login", {"username": username, "password": password}, self.csrf()
        )


def data_of(payload):
    return (payload or {}).get("data") or {}


def find_account(admin, username):
    _, payload = admin.send("GET", "/api/v1/admin/core/accounts?limit=200")
    for row in data_of(payload) or []:
        if row.get("username") == username:
            return row
    return None


def main():
    admin = Session()
    status, payload = admin.login(ADMIN_USER, ADMIN_PASS)
    check("管理员登录", status == 200, "HTTP %s" % status)
    if status != 200:
        return finish()

    probe = find_account(admin, PROBE_USER)
    check("探测账号存在（先跑 smoke-authz-matrix.py 创建）", probe is not None)
    if not probe:
        return finish()
    probe_id = str(probe.get("id"))

    # ---- 1. 未登录不能改密 ----
    anonymous = Session()
    status, _ = anonymous.post(
        "/api/v1/auth/password", {"currentPassword": "x", "newPassword": "Whatever2026x"}
    )
    check("未登录调用改密返回 401", status == 401, "HTTP %s" % status)

    # ---- 2. 普通用户不能重置他人 ----
    user = Session()
    status, _ = user.login(PROBE_USER, PROBE_PASS)
    check("普通用户以原口令登录（前提）", status == 200, "HTTP %s" % status)
    if status != 200:
        return finish()
    status, payload = user.post(
        "/api/v1/admin/core/accounts/%s/password-reset" % probe_id, {"reason": "越权尝试"}
    )
    check("普通用户重置他人口令被拒", status == 403, "HTTP %s" % status)
    check("被拒响应不含一次性口令", "temporaryPassword" not in json.dumps(payload, ensure_ascii=False))

    # ---- 3. 管理员不能重置自己 ----
    admin_id = str((find_account(admin, ADMIN_USER) or {}).get("id") or "")
    if admin_id:
        status, payload = admin.post(
            "/api/v1/admin/core/accounts/%s/password-reset" % admin_id, {"reason": "自测"}
        )
        check(
            "管理员重置自己被拒（应走本人改密）",
            status == 422,
            "HTTP %s / %s" % (status, payload.get("code")),
        )

    # ---- 4. 管理员重置探测账号：拿一次性口令 ----
    status, payload = admin.post(
        "/api/v1/admin/core/accounts/%s/password-reset" % probe_id,
        {"reason": "账号口令自检：模拟本人遗忘口令"},
    )
    reset = data_of(payload)
    temporary = str(reset.get("temporaryPassword") or "")
    check("管理员重置口令成功", status == 200, "HTTP %s" % status)
    check("一次性口令为 16 位", len(temporary) == 16, len(temporary))
    check("响应明确标记需修改", reset.get("mustChangePassword") is True)
    check("响应说明口令只出现一次", "只在本响应中出现一次" in str(reset.get("note") or ""))

    # 旧会话（重置前登录的那个）必须已经不能用了
    status, _ = user.send("GET", "/api/v1/account/portals")
    check("重置后原会话立即失效", status == 401, "HTTP %s" % status)

    # ---- 5. 用一次性口令登录：成功且带需改标记 ----
    temp_session = Session()
    status, payload = temp_session.login(PROBE_USER, temporary)
    check("一次性口令可登录", status == 200, "HTTP %s" % status)
    check("登录响应标记需修改口令", data_of(payload).get("mustChangePassword") is True)

    status, payload = temp_session.send("GET", "/api/v1/auth/me")
    check("/auth/me 同样标记需修改", data_of(payload).get("mustChangePassword") is True)

    # ---- 6. 用一次性口令改密 ----
    status, payload = temp_session.post(
        "/api/v1/auth/password", {"currentPassword": temporary, "newPassword": TEMP_PASS}
    )
    changed = data_of(payload)
    check("本人改密成功", status == 200, "HTTP %s" % status)
    check("改密响应声明其他会话已失效", "其他设备上的会话已失效" in str(changed.get("note") or ""))
    check("改密后标记清除", changed.get("mustChangePassword") is False)

    # 当前会话被续期：改密后立刻请求自己的账户端点仍应 200
    status, _ = temp_session.send("GET", "/api/v1/account/portals")
    check("改密不会把当前会话踢下线", status == 200, "HTTP %s" % status)

    # ---- 7. 旧口令（一次性口令）必须立刻失效 ----
    stale = Session()
    status, _ = stale.login(PROBE_USER, temporary)
    check("改密后一次性口令立即失效", status == 401, "HTTP %s" % status)

    # ---- 8. 新口令可用，且不再要求修改 ----
    fresh = Session()
    status, payload = fresh.login(PROBE_USER, TEMP_PASS)
    check("新口令可登录", status == 200, "HTTP %s" % status)
    check("新口令登录不再要求修改", data_of(payload).get("mustChangePassword") is False)

    # ---- 9. 还原为固定口令，保证脚本可重复跑 ----
    status, _ = fresh.post(
        "/api/v1/auth/password", {"currentPassword": TEMP_PASS, "newPassword": PROBE_PASS}
    )
    check("口令已还原为自检固定值（脚本可重复执行）", status == 200, "HTTP %s" % status)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
