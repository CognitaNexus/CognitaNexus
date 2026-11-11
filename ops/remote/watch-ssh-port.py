#!/usr/bin/env python3
"""盯住目标机的 SSH 端口，每 10 分钟从公网节点探一次，直到通了就退出。

为什么不用本地探测：本会话的执行环境没有直连能力（所有 TCP 经 sandbox-cli
出网，到目标 IP 全端口 502），本地探测恒为失败，测不出真实状态。check-host
的多国节点是独立第三方视角，能反映「外面的人到底连不连得上」。

判定口径：
  通  -> 节点返回的是耗时数字，形如 [0.234, "OK"]
  断  -> 节点返回 {"error": "Connection refused"}（服务器没监听）
        或 {"error": "Connection timed out"}（包被吞，云安全列表/防火墙）
两种 error 的区别很重要，见 PITFALLS-OPS.md。

用法：
  python watch-ssh-port.py                     # 默认 150.230.223.11:22，10 分钟一轮
  CN_TARGET_PORT=8443 python watch-ssh-port.py # 换端口
  CN_PROBE_ONCE=1 python watch-ssh-port.py     # 只探一次，不进循环
"""
import json
import os
import sys
import time
import urllib.request

HOST = os.environ.get("CN_TARGET_HOST", "150.230.223.11")
# 支持逗号分隔多端口，例如 CN_TARGET_PORT=22,8443
PORTS = [p.strip() for p in os.environ.get("CN_TARGET_PORT", "22,8443").split(",") if p.strip()]
PORT = PORTS[0] if PORTS else "22"
INTERVAL = int(os.environ.get("CN_PROBE_INTERVAL", "600"))  # 秒
NODES = int(os.environ.get("CN_PROBE_NODES", "3"))
SITE_PORT = os.environ.get("CN_SITE_PORT", "80")
ONCE = os.environ.get("CN_PROBE_ONCE") == "1"

# 两条出口各司其职：
#   check-host 走工作区代理（6733）可达
#   目标机本身走用户代理（7890），因为 6733 到该 IP 一律 502
PROBE_PROXY = os.environ.get("CN_PROBE_PROXY", "http://127.0.0.1:6733")
SITE_PROXY = os.environ.get("CN_SITE_PROXY", "http://127.0.0.1:7890")

_probe_opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": PROBE_PROXY, "https": PROBE_PROXY})
)
_site_opener = urllib.request.build_opener(
    urllib.request.ProxyHandler({"http": SITE_PROXY, "https": SITE_PROXY})
)


def stamp() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")


def log(msg: str) -> None:
    line = f"[{stamp()}] {msg}"
    print(line, flush=True)


def check_host_api(path: str) -> dict:
    req = urllib.request.Request(
        "https://check-host.net" + path,
        headers={"Accept": "application/json", "User-Agent": "cn-port-watch/1.0"},
    )
    with _probe_opener.open(req, timeout=30) as resp:
        return json.load(resp)


def probe_port(port: str) -> tuple[bool, str]:
    """返回 (是否至少一个节点连通, 人类可读明细)。"""
    start = check_host_api(f"/check-tcp?host={HOST}:{port}&max_nodes={NODES}")
    rid = start.get("request_id")
    if not rid:
        return False, f"申请探测失败：{str(start)[:120]}"

    # check-host 是异步的：先拿 request_id，再轮询结果。各节点回得很不齐，
    # 单次 14 秒常常拿到一堆空数组（表现为「通0/0」，比误报断还误导），
    # 所以必须轮询到出现实质结果为止。
    result: dict = {}
    for wait in (12, 6, 6, 6, 6):
        time.sleep(wait)
        result = check_host_api(f"/check-result/{rid}")
        if any(payload for payload in result.values()):
            break

    ok, bad = [], []
    for node, payload in result.items():
        if not payload:
            continue
        item = payload[0]
        if isinstance(item, dict) and "error" in item:
            bad.append(f"{node.split('.')[0]}={item['error'].replace('Connection ', '')}")
        elif isinstance(item, list) and item and isinstance(item[0], (int, float)):
            ok.append(f"{node.split('.')[0]}={item[0]:.2f}s")

    if not ok and not bad:
        # 一个都没回来，不代表端口断，只是这轮没测出来，别当成结论记
        return False, "本轮未取到有效节点结果"

    detail = f"通{len(ok)}/{len(ok) + len(bad)}"
    if ok:
        detail += " (" + ", ".join(ok) + ")"
    if bad:
        detail += " | 断: " + ", ".join(bad)
    return bool(ok), detail


def probe_site() -> str:
    """顺手记录网站是否活着，用来区分「整机挂了」和「只是 SSH 没开」。"""
    url = f"http://{HOST}:{SITE_PORT}/"
    try:
        with _site_opener.open(url, timeout=12) as resp:
            return f"HTTP {resp.status}"
    except Exception as exc:  # noqa: BLE001 - 这里只要一个可读结论
        return f"不可达({type(exc).__name__})"


def main() -> int:
    log(f"开始盯 {HOST} 的 {'/'.join(PORTS)} 端口，每 {INTERVAL}s 一轮，网站 {SITE_PORT} 作对照")
    round_no = 0
    while True:
        round_no += 1
        try:
            site = probe_site()
        except Exception as exc:  # noqa: BLE001 - 只作参考，失败不影响主线
            site = f"探测出错({type(exc).__name__})"

        reopened = []
        for port in PORTS:
            try:
                up, detail = probe_port(port)
            except Exception as exc:  # noqa: BLE001 - 网络抖动不该让巡逻退出
                log(f"第 {round_no} 轮 {port}：探测出错 {type(exc).__name__}: {exc}")
                continue
            if up:
                log(f"第 {round_no} 轮：端口 {port} 已连通 {detail} | 网站 {site}")
                reopened.append(port)
            else:
                log(f"第 {round_no} 轮：端口 {port} 仍不通 {detail} | 网站 {site}")

        if reopened:
            log(f"RECOVERED {HOST}:{','.join(reopened)} —— 可以开始干活了")
            return 0
        if ONCE:
            return 1
        time.sleep(INTERVAL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("被手动中断")
        sys.exit(130)
