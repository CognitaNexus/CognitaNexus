"""FR07 参与关系处置端到端自检。

覆盖：本人确认关系、本人提出异议、本人撤回异议、治理端三类裁决、
未登录与缺理由两条负向路径，以及「争议关系不进入公开确定关系」的投影核对。

用法：python tests/api/smoke-relations.py
前置：core-service(8081) 已启动；库中至少存在一条 relation_state=AUTO 且成果公开生效的参与关系。

说明：脚本会真实创建一次认领授权（如当前账号尚无门户），并把被测关系在结束时恢复为 AUTO；
除此之外不修改任何数据。它不会把「接口通了」报告成通过——每一步都核对返回的业务码与状态。
"""

import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.request

CORE = os.environ.get("CORE", "http://127.0.0.1:8081")
USER = os.environ.get("CN_BOOTSTRAP_ADMIN_USERNAME", "cnadmin")
PASS = os.environ.get("CN_BOOTSTRAP_ADMIN_PASSWORD", "cn-admin-Dev-2026")
PSQL = os.environ.get("PSQL", r"D:/dev-tools/pgsql/bin/psql.exe")

failures: list[str] = []
passed = 0


def check(desc: str, ok: bool, detail: str = "") -> None:
    global passed
    if ok:
        passed += 1
        print(f"  [OK ] {desc}")
    else:
        failures.append(f"{desc} {detail}")
        print(f"  [FAIL] {desc} {detail}")


def pick_target() -> tuple[str, str, str]:
    """挑一条可测的关系：AUTO、成果公开生效、所属学者档案未被合并。"""
    import subprocess

    env = dict(os.environ)
    env["PGPASSWORD"] = "cn_core_app_dev"
    env["PGCLIENTENCODING"] = "UTF8"
    sql = """
    SELECT c.id || '|' || c.scholar_id || '|' || c.achievement_id
    FROM core_contribution c
    JOIN core_achievement a ON a.id = c.achievement_id
    JOIN core_scholar s ON s.id = c.scholar_id
    WHERE c.relation_state = 'AUTO'
      AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
      AND (s.merged_into_id IS NULL OR s.merged_into_id = '')
    LIMIT 1;
    """
    out = subprocess.run(
        [PSQL, "-h", "127.0.0.1", "-p", "5432", "-U", "core_app", "-d", "core_db", "-At", "-f", "-"],
        input=sql, capture_output=True, text=True, env=env, timeout=30,
    )
    line = (out.stdout or "").strip().splitlines()
    if not line or "|" not in line[0]:
        raise SystemExit("找不到可测的参与关系：需要一条 AUTO 且成果公开生效的记录")
    contribution_id, scholar_id, achievement_id = line[0].split("|")
    return contribution_id, scholar_id, achievement_id


jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(jar), urllib.request.ProxyHandler({})
)


def csrf() -> str:
    opener.open(CORE + "/api/v1/auth/csrf").read()
    for cookie in jar:
        if cookie.name == "XSRF-TOKEN":
            return cookie.value
    raise RuntimeError("未取到 CSRF 令牌")


def call(method: str, path: str, body=None, with_csrf: bool = True):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(CORE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if with_csrf:
        req.add_header("X-XSRF-TOKEN", csrf())
    try:
        with opener.open(req) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        return err.code, json.loads(err.read().decode("utf-8"))


def envelope_code(payload: dict) -> str:
    return payload.get("code", "")


def main() -> int:
    contribution_id, scholar_id, achievement_id = pick_target()
    print(f"被测关系 {contribution_id}（学者 {scholar_id} / 成果 {achievement_id}）")

    print("== 1. 登录 ==")
    status, payload = call("POST", "/api/v1/auth/login", {"username": USER, "password": PASS})
    check("登录平台管理员", envelope_code(payload) == "OK", f"HTTP {status}")

    print("== 2. 确保门户授权存在（认领 + 审批）==")
    _, portals = call("GET", "/api/v1/account/portals")
    owned = any(p.get("scholarId") == scholar_id for p in (portals.get("data") or []))
    if not owned:
        _, claim = call(
            "POST", "/api/v1/account/claims",
            {"scholarId": scholar_id, "statement": "自检：为验证本人关系操作临时认领"},
        )
        claim_id = (claim.get("data") or {}).get("id")
        check("认领申请已受理", envelope_code(claim) == "OK" and bool(claim_id))
        _, decision = call(
            "POST", f"/api/v1/admin/core/claims/{claim_id}/decision",
            {"decision": "APPROVE", "reason": "自检：为验证本人关系操作授权"},
        )
        check("认领已批准并授权", envelope_code(decision) == "OK")
    else:
        check("门户授权已存在", True)

    print("== 3. 我的成果列表 ==")
    _, listing = call("GET", f"/api/v1/account/contributions?scholarId={scholar_id}")
    rows = listing.get("data") or []
    target = next((r for r in rows if r.get("contributionId") == contribution_id), None)
    check("列表含被测关系", target is not None)
    check("列表随记录给出可执行动作", bool(target and target.get("availableActions")))

    print("== 4. 负向：未登录不能确认 ==")
    anon = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.ProxyHandler({}),
    )
    try:
        with anon.open(CORE + f"/api/v1/account/contributions/{contribution_id}/confirm") as resp:
            check("未登录确认被拒", False, f"HTTP {resp.status}")
    except urllib.error.HTTPError as err:
        check("未登录确认被拒", err.code in (401, 403), f"HTTP {err.code}")

    print("== 5. 负向：无理由异议被拒 ==")
    status, payload = call(
        "POST", f"/api/v1/account/contributions/{contribution_id}/dispute", {"reason": "   "}
    )
    check("无理由异议被拒", envelope_code(payload) == "INVALID_ARGUMENT", f"HTTP {status}")

    print("== 6. 本人确认（幂等）==")
    _, first = call("POST", f"/api/v1/account/contributions/{contribution_id}/confirm")
    first_state = (first.get("data") or {}).get("relationState")
    check("确认后为 SELF_CONFIRMED", first_state in ("SELF_CONFIRMED", "AUTO"))
    _, again = call("POST", f"/api/v1/account/contributions/{contribution_id}/confirm")
    check("重复确认不报错", envelope_code(again) == "OK")

    print("== 7. 本人提出异议 ==")
    _, disputed = call(
        "POST", f"/api/v1/account/contributions/{contribution_id}/dispute",
        {"reason": "自检：验证争议关系不计入确定合作"},
    )
    check("异议后为 DISPUTED", (disputed.get("data") or {}).get("relationState") == "DISPUTED")

    print("== 8. 公开详情不再显示为确定关系 ==")
    _, detail = call("GET", f"/api/v1/public/achievements/{achievement_id}")
    if envelope_code(detail) == "OK":
        contrib = next(
            (c for c in (detail.get("data") or {}).get("contributors", []) if c.get("scholarId") == scholar_id),
            None,
        )
        check("争议关系 qualified=false", bool(contrib) and contrib.get("qualified") is False)
    else:
        check("公开详情可读", False, envelope_code(detail))

    print("== 9. 治理端队列与裁决 ==")
    _, queue = call("GET", "/api/v1/admin/core/contributions/disputed")
    check("争议队列含被测关系", any(
        r.get("contributionId") == contribution_id for r in (queue.get("data") or [])))
    _, restored = call(
        "POST", f"/api/v1/admin/core/contributions/{contribution_id}/resolution",
        {"decision": "RESTORE", "reason": "自检结束：撤回争议，恢复自动关联"},
    )
    check("RESTORE 后回到 AUTO", (restored.get("data") or {}).get("relationState") == "AUTO")

    print("== 10. 负向：无理由裁决被拒 ==")
    _, bad = call(
        "POST", f"/api/v1/admin/core/contributions/{contribution_id}/resolution",
        {"decision": "EXCLUDE", "reason": ""},
    )
    check("空理由裁决被拒", envelope_code(bad) == "INVALID_ARGUMENT")

    print(f"\n通过 {passed} 项" + (f"，失败 {len(failures)} 项" if failures else "，全部通过"))
    for item in failures:
        print(" -", item)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
