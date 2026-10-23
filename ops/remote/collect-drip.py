#!/usr/bin/env python3
"""采集滴灌脚本：每 2 小时只抓一条。

背景：平台的后台执行器是「任务 RUNNING 就每 2 秒推进一页」，没有内建的慢速节奏。
所以慢速采集只能从外部驱动——cron 每 2 小时调一次本脚本，每次只跑一轮，
任务本身保持 PAUSED，后台执行器不去碰它。

用法：
    python3 collect-drip.py --init      # 首次：改来源参数、建任务、暂停、跑一轮
    python3 collect-drip.py             # 日常：登录、跑一轮、打一行结果（cron 用这个）

可用环境变量覆盖：
    DRIP_SOURCE     来源编码，默认 openalex
    DRIP_JOB_KEY    任务标识，默认 prod-drip
    DRIP_QUERY      关键词，默认 software engineering
    DRIP_PAGE_SIZE  每轮抓几条，默认 1
"""

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime

CORE = "http://127.0.0.1:8081"
ING = "http://127.0.0.1:8082"
CONF = "/home/buaa/cn/conf/cn-env.sh"
PWD_FILE = "/home/buaa/cn-deploy-scripts/ADMIN-PASSWORD.txt"
ADMIN_USER = os.environ.get("DRIP_ADMIN_USER", "cnadmin")

SOURCE_CODE = os.environ.get("DRIP_SOURCE", "openalex")
JOB_KEY = os.environ.get("DRIP_JOB_KEY", "prod-drip")
QUERY = os.environ.get("DRIP_QUERY", "software engineering")
PAGE_SIZE = int(os.environ.get("DRIP_PAGE_SIZE", "1"))


def stamp():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def admin_password():
    """口令文件最后一行是口令，前面是说明文字。"""
    with open(PWD_FILE, encoding="utf-8") as fh:
        lines = [line.strip() for line in fh if line.strip()]
    if not lines:
        raise SystemExit("口令文件为空：%s" % PWD_FILE)
    return lines[-1]


class Admin:
    """登录一次，之后每次 POST 前重新取 CSRF 令牌。

    登录会 changeSessionId，旧的 CSRF 令牌跟着失效，所以不能复用登录前取的那一个。
    """

    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        body = json.dumps(
            {"username": ADMIN_USER, "password": admin_password()}
        ).encode()
        token = self._csrf()
        req = urllib.request.Request(
            CORE + "/api/v1/auth/login",
            data=body,
            headers={"Content-Type": "application/json", token[0]: token[1]},
            method="POST",
        )
        self.op.open(req)

    def _csrf(self):
        data = json.load(self.op.open(CORE + "/api/v1/auth/csrf"))["data"]
        return data["headerName"], data["token"]

    def get(self, url):
        return json.load(self.op.open(url))

    def post(self, url, payload=None):
        header, token = self._csrf()
        data = None if payload is None else json.dumps(payload).encode()
        req = urllib.request.Request(
            url,
            data=data if data is not None else b"",
            headers={"Content-Type": "application/json", header: token},
            method="POST",
        )
        return json.load(self.op.open(req))

    def patch(self, url, payload):
        header, token = self._csrf()
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", header: token},
            method="PATCH",
        )
        return json.load(self.op.open(req))


def items_of(body):
    """列表接口的包装层不统一，这里两种形态都认。"""
    data = body.get("data") or {}
    for key in ("items", "content", "list", "jobs", "sources"):
        value = data.get(key)
        if isinstance(value, list):
            return value
    return data if isinstance(data, list) else []


def find_source(admin, code):
    for row in items_of(admin.get(ING + "/api/v1/admin/ingestion/sources")):
        if row.get("sourceCode") == code or row.get("source_code") == code:
            return row
    raise SystemExit("找不到来源 %s" % code)


def find_job(admin, key):
    for row in items_of(admin.get(ING + "/api/v1/admin/ingestion/jobs")):
        if row.get("jobKey") == key or row.get("job_key") == key:
            return row
    return None


def source_id(row):
    return row.get("id") or row.get("sourceId")


def job_id(row):
    return row.get("id") or row.get("jobId")


def do_init(admin):
    src = find_source(admin, SOURCE_CODE)
    sid = source_id(src)
    print("来源 %s id=%s 现配置 pageSize=%s query=%s"
          % (SOURCE_CODE, sid, src.get("pageSize"), src.get("queryExpression")))

    admin.patch(
        "%s/api/v1/admin/ingestion/sources/%s" % (ING, sid),
        {
            "queryExpression": QUERY,
            "pageSize": PAGE_SIZE,
            "reason": "改为慢速滴灌：每 2 小时一轮、每轮 %d 条" % PAGE_SIZE,
        },
    )
    after = find_source(admin, SOURCE_CODE)
    print("改后配置 pageSize=%s query=%s" % (after.get("pageSize"), after.get("queryExpression")))

    job = find_job(admin, JOB_KEY)
    if job is None:
        admin.post(
            ING + "/api/v1/admin/ingestion/jobs",
            {"sourceId": sid, "jobKey": JOB_KEY, "reason": "慢速滴灌链路验证"},
        )
        # 建完重新查一次，不依赖创建响应：任务一旦落库，后台执行器可能在几百毫秒内
        # 就把它领走并把状态改成 RUNNING，创建响应里的字段与实际状态未必一致。
        job = find_job(admin, JOB_KEY)
        if job is None:
            raise SystemExit("任务 %s 建完却查不到，检查 ingestion 日志" % JOB_KEY)
        print("已建任务 %s id=%s 状态=%s" % (JOB_KEY, job_id(job), job.get("status")))
    else:
        print("任务已存在 %s id=%s 状态=%s" % (JOB_KEY, job_id(job), job.get("status")))

    if job.get("status") != "PAUSED":
        admin.post(
            "%s/api/v1/admin/ingestion/jobs/%s/pause" % (ING, job_id(job)),
            {"reason": "保持暂停，由 cron 每 2 小时驱动一轮"},
        )
        print("已置为 PAUSED（后台执行器不再推进）")


def do_one_round(admin):
    job = find_job(admin, JOB_KEY)
    if job is None:
        raise SystemExit("任务 %s 不存在，先跑 --init" % JOB_KEY)
    jid = job_id(job)
    base = "%s/api/v1/admin/ingestion/jobs/%s" % (ING, jid)
    out = {}
    try:
        # 必须先 resume 再 run：runOnce 对 PAUSED 是直接跳过的（返回「本轮跳过」），
        # 这是平台既有语义，不是 bug。跑完立刻 pause，把任务摁回去，避免后台执行器
        # 每 2 秒一页地自己往前跑。
        admin.post(base + "/resume", {"reason": "cron 滴灌：放开一轮"})
        out = admin.post(base + "/run?maxRounds=1").get("data") or {}
    finally:
        try:
            admin.post(base + "/pause", {"reason": "cron 滴灌：本轮结束，摁回暂停"})
        except Exception as exc:  # noqa: BLE001
            print("%s 警告：回置暂停失败 %s，下轮会补上" % (stamp(), exc))

    print(
        "%s rounds=%s staged=%s handedOff=%s failed=%s msg=%s"
        % (
            stamp(),
            out.get("rounds"),
            out.get("staged"),
            out.get("handedOff"),
            out.get("fetchFailed"),
            (out.get("lastMessage") or "")[:70],
        )
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--init", action="store_true", help="首次配置：改来源、建任务、暂停")
    args = parser.parse_args()

    admin = Admin()
    if args.init:
        do_init(admin)
    do_one_round(admin)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")[:300]
        print("%s 请求失败 %s %s" % (stamp(), exc.code, body))
        sys.exit(1)
