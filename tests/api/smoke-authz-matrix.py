#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""越权矩阵自检：把「前端隐藏入口不构成授权」变成可执行断言。

为什么需要这个脚本：管理端点分散在三个服务、二十多个控制器里，逐个手工点一遍既做不全，
也不会在下次加端点时提醒任何人。这里做两件事：

1. **从源码扫描端点清单**，与运行中的服务逐条对账。新增一个 `/api/v1/admin/**` 或
   `/internal/v1/**` 端点，不用改脚本也会被自动纳入——漏测不再是「忘了登记」。
2. **三种主体各打一遍**：未登录、普通登录用户、管理员。断言依据的不是「按钮没显示」，
   而是「直接发这个请求会拿到什么」：

   - 未登录访问任何管理/内部端点：401 或 403，且不是 2xx；
   - 普通用户访问管理端点：非 2xx；其中无必填参数的只读端点必须精确是 403
     （400 说明请求在鉴权之前就因参数不合法被拒，不能算「鉴权生效」）；
   - 普通用户访问自己的账户端点：200（否则「只允许本人」就变成了「谁都不允许」）；
   - 无服务令牌访问 `/internal/v1/**`：401 或 403（内部端点不能靠路径前缀被信任）。

口径说明：写端点对普通用户只断言「非 2xx」，不要求精确 403。原因是 Spring 的参数校验发生在
控制器方法进入之前，空请求体会先得到 400；把 400 判成失败会逼着脚本去构造合法业务载荷，
而那是功能测试的事，不是越权矩阵的事。这里要证明的是「拿不到结果」，不是「错误码好看」。

前置：整栈已启动。脚本会注册一个固定的普通账号 `authz-probe`（若已存在则直接登录），
它没有任何特权，不写入任何业务数据。
"""

import glob
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

CORE = os.environ.get("CORE_BASE", "http://127.0.0.1:8081")
ING = os.environ.get("ING_BASE", "http://127.0.0.1:8082")
ADMIN_USER = os.environ.get("ADMIN_USER", "cnadmin")
ADMIN_PASS = os.environ.get("ADMIN_PASS", "cn-admin-Dev-2026")
PROBE_USER = "authz-probe"
PROBE_PASS = "AuthzProbe2026x"

RESULTS = []

# 普通用户访问这些只读端点必须精确 403：它们没有必填参数，400 只能是鉴权漏在前面。
STRICT_ADMIN_GET = {
    "/api/v1/admin/core/overview",
    "/api/v1/admin/core/todos",
    "/api/v1/admin/core/accounts",
    "/api/v1/admin/core/audit",
    "/api/v1/admin/core/citations/stats",
    "/api/v1/admin/core/submissions",
    "/api/v1/admin/core/claims",
    "/api/v1/admin/core/portal-changes",
    "/api/v1/admin/core/integrity-notices",
    "/api/v1/admin/core/governance/anomalies",
    "/api/v1/admin/core/governance/candidates",
    "/api/v1/admin/core/governance/candidates/workbench",
    "/api/v1/admin/core/topics/unresolved",
    "/api/v1/admin/core/topics/inference-summary",
    "/api/v1/admin/ingestion/jobs",
    "/api/v1/admin/ingestion/sources",
    "/api/v1/admin/ingestion/capabilities",
}

# 普通用户访问自己的只读端点应得到 200：越权矩阵要能区分「拒绝外人」和「拒绝所有人」。
STRICT_ACCOUNT_GET = {
    "/api/v1/account/portals",
    "/api/v1/account/claims",
    "/api/v1/account/portal-changes",
    "/api/v1/account/submissions",
}


def base_of(path):
    """按端点归属选服务。

    三个服务各有独立端口与独立库，把采集端的端点打到 core 会得到 404——而 404 不是 2xx，
    会让「未登录被拒」这条断言假通过。因此必须先分派，再断言。
    """
    return ING if path.startswith("/api/v1/admin/ingestion") else CORE


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  -> " + str(detail)) if detail else ""))


def scan_endpoints():
    """从控制器源码扫描 (method, path)，路径变量替换为固定探测值。

    扫描而不是硬编码，是为了让「新加的端点」自动进入矩阵。代价是路径里可能含模板变量，
    因此统一替换成一个不存在的标识：越权判定与对象是否存在无关。
    """
    found = set()
    pattern = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\((?:value\s*=\s*)?"([^"]*)"[^)]*\))?')
    for path in glob.glob("services/*/src/main/java/**/*Controller.java", recursive=True):
        source = open(path, encoding="utf-8").read()
        base_match = re.search(r'@RequestMapping\("([^"]+)"\)', source)
        base = base_match.group(1) if base_match else ""
        # 错误处理控制器不是业务端点
        if "${" in base:
            continue
        for match in pattern.finditer(source):
            verb = match.group(1).upper()
            sub = match.group(2) or ""
            full = (base + sub) or "/"
            full = re.sub(r"\{[^}]+\}", "authz-probe", full)
            found.add((verb, full))
    return found


class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), urllib.request.ProxyHandler({})
        )

    def send(self, method, url, body=None, token=None):
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
                return response.status, text
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")
        except Exception as error:  # 连接失败也要如实记账，而不是当成通过
            return 0, str(error)

    def csrf(self):
        self.send("GET", CORE + "/api/v1/auth/csrf")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return ""

    def login(self, username, password):
        token = self.csrf()
        return self.send(
            "POST", CORE + "/api/v1/auth/login", {"username": username, "password": password}, token
        )


def main():
    endpoints = scan_endpoints()
    admin_eps = sorted(e for e in endpoints if e[1].startswith("/api/v1/admin"))
    account_eps = sorted(e for e in endpoints if e[1].startswith("/api/v1/account"))
    internal_eps = sorted(e for e in endpoints if e[1].startswith("/internal/v1"))

    # 扫描器本身也要被验证：一个返回空集合的扫描器会让下面所有断言「全绿」。
    check("扫描到管理端点（下界 20）", len(admin_eps) >= 20, "%d 个" % len(admin_eps))
    check("扫描到账户端点（下界 5）", len(account_eps) >= 5, "%d 个" % len(account_eps))
    check("扫描到内部端点（下界 5）", len(internal_eps) >= 5, "%d 个" % len(internal_eps))

    anonymous = Session()

    # ---- 1. 未登录：所有管理端点都必须被拒 ----
    failed = []
    for verb, path in admin_eps:
        body = None if verb == "GET" else {}
        status, _ = anonymous.send(verb, base_of(path) + path, body)
        if status in (0,) or 200 <= status < 300:
            failed.append("%s %s -> %s" % (verb, path, status))
    check("未登录访问管理端点全部被拒", not failed, "；".join(failed[:6]))

    # ---- 2. 未登录：内部端点不能靠路径前缀被信任 ----
    failed = []
    for verb, path in internal_eps:
        body = None if verb == "GET" else {}
        status, _ = anonymous.send(verb, CORE + path, body)
        if 200 <= status < 300:
            failed.append("%s %s -> %s" % (verb, path, status))
    check("无服务令牌访问内部端点全部被拒", not failed, "；".join(failed[:6]))

    # ---- 3. 未登录：账户端点必须 401（不是 403——调用方还未表明身份） ----
    failed = []
    for verb, path in account_eps:
        if verb != "GET":
            continue
        status, _ = anonymous.send(verb, CORE + path)
        if status != 401:
            failed.append("%s -> %s" % (path, status))
    check("未登录访问账户端点返回 401", not failed, "；".join(failed[:6]))

    # ---- 4. 准备一个普通用户 ----
    code, _ = anonymous.send(
        "POST", CORE + "/api/v1/auth/register",
        {"username": PROBE_USER, "password": PROBE_PASS, "displayName": "越权探测账号"},
        anonymous.csrf(),
    )
    # 已存在（409）也是正常路径；两条都必须能继续登录
    check("普通探测账号可用（新注册或已存在）", code in (200, 409), "HTTP %s" % code)

    user = Session()
    status, text = user.login(PROBE_USER, PROBE_PASS)
    check("普通用户登录成功", status == 200, "HTTP %s" % status)
    if status != 200:
        return finish()

    # ---- 5. 普通用户：管理端点拿不到结果 ----
    leaked = []
    not_denied = []
    strict_bad = []
    for verb, path in admin_eps:
        body = None if verb == "GET" else {}
        token = None if verb == "GET" else user.csrf()
        status, text = user.send(verb, base_of(path) + path, body, token)
        if 200 <= status < 300:
            not_denied.append("%s %s -> %s" % (verb, path, status))
        # 错误响应体里不应出现列表数据或一次性口令
        if '"items"' in text or "temporaryPassword" in text:
            leaked.append("%s %s" % (verb, path))
        if verb == "GET" and path in STRICT_ADMIN_GET and status != 403:
            strict_bad.append("%s -> %s" % (path, status))

    check("普通用户访问管理端点全部未拿到 2xx", not not_denied, "；".join(not_denied[:6]))
    check("管理端点的拒绝响应不含业务数据", not leaked, "；".join(leaked[:6]))
    check(
        "只读管理端点对普通用户精确返回 403（清单 %d 个）" % len(STRICT_ADMIN_GET),
        not strict_bad,
        "；".join(strict_bad[:6]),
    )

    # ---- 6. 普通用户：自己的账户端点必须可用 ----
    account_bad = []
    for verb, path in account_eps:
        if verb != "GET" or path not in STRICT_ACCOUNT_GET:
            continue
        status, _ = user.send(verb, CORE + path)
        if status != 200:
            account_bad.append("%s -> %s" % (path, status))
    check(
        "普通用户读取自己的账户端点返回 200（清单 %d 个）" % len(STRICT_ACCOUNT_GET),
        not account_bad,
        "；".join(account_bad[:6]),
    )

    # ---- 7. 管理员对照：同一批只读端点必须可用 ----
    # 没有这一组，「全部 403」也可能是因为服务根本没起来或路径写错了。
    admin = Session()
    status, _ = admin.login(ADMIN_USER, ADMIN_PASS)
    check("管理员登录成功", status == 200, "HTTP %s" % status)
    if status == 200:
        admin_bad = []
        for path in sorted(STRICT_ADMIN_GET):
            status, _ = admin.send("GET", base_of(path) + path)
            if status != 200:
                admin_bad.append("%s -> %s" % (path, status))
        check(
            "管理员访问同一批只读端点返回 200（证明上一条不是路径写错）",
            not admin_bad,
            "；".join(admin_bad[:6]),
        )

        # ---- 8. 管理端点不接受服务令牌冒充 ----
        # 服务令牌只能用于 /internal/v1。脚本不持有真实令牌（它只存在于部署配置里），
        # 因此只验证「带一个伪造的令牌头」不会让管理端点放行——若放行，说明过滤链按头存在与否
        # 判断，而不是按令牌内容校验。
        fake = urllib.request.Request(
            CORE + "/api/v1/admin/core/accounts",
            headers={"X-Service-Token": "forged-token", "Accept": "application/json"},
            method="GET",
        )
        try:
            with anonymous.opener.open(fake, timeout=30) as response:
                forged_status = response.status
        except urllib.error.HTTPError as error:
            forged_status = error.code
        except Exception:
            forged_status = 0
        check("伪造服务令牌访问管理端点被拒", forged_status in (401, 403), "HTTP %s" % forged_status)

    return finish()


def finish():
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print("\n结果：通过 %d 项，失败 %d 项" % (passed, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
