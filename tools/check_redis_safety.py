#!/usr/bin/env python3
"""Redis 部署前置检查 —— 只读探针, 不改任何配置。

为什么需要它:
  位图(座位占用)是**唯一的防超卖防线**。Redis 这边一旦配错, 症状不是
  「报错」, 而是「座位凭空变成可选」:

  * ``maxmemory-policy`` 不是 ``noeviction`` → Redis 开始驱逐键,
    锁定位图被驱逐 = 那批座位立刻回到可选池 → **同一座位卖两次**;
  * 位图键上挂了 TTL → 会场次演到一半键自己没了 → 同样凭空释放;
  * ``maxmemory`` 没设 → 位图被一个越界 offset 撑到 12KB(实测),
    内存涨上去之后同样进驱逐流程。

  这些配置错了不会在启动时失败, 只在抢票高峰炸。本脚本把「启动后到
  开抢之前」该看的项一次性查完, 打印成一份可直接对照的清单。

**只读**: 本脚本只发 INFO / CONFIG GET / SCAN / STRLEN / TTL 一类查询,
  绝不写、绝不改配置。

用法:
    python tools/check_redis_safety.py                # 全查
    python tools/check_redis_safety.py --host 10.0.0.5 --port 6379

退出码:
    0 = 全部通过(可能有警告); 1 = 有阻断级问题; 2 = 连不上 Redis。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

REDIS_HOST = os.environ.get("CINEMA_REDIS_HOST", "127.0.0.1")
REDIS_PORT = os.environ.get("CINEMA_REDIS_PORT", "6379")
REDIS_PREFIX = os.environ.get("CINEMA_REDIS_PREFIX", "cinema:")

#: 级别: PASS=通过 / WARN=该知道但不阻断 / FAIL=必须改, 否则会出事
PASS, WARN, FAIL = "PASS", "WARN", "FAIL"

LEVEL_ICON = {PASS: "[PASS]", WARN: "[WARN]", FAIL: "[FAIL]"}


def redis_cli(host: str, port: str, *args) -> str:
    p = subprocess.run(["redis-cli", "-h", host, "-p", port, *[str(a) for a in args]],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError((p.stderr or "").strip())
    return p.stdout.strip()


def config_get(host: str, port: str, key: str) -> str:
    """CONFIG GET <key> —— 返回第二列(值)。"""
    out = redis_cli(host, port, "CONFIG", "GET", key)
    lines = [l for l in out.splitlines() if l.strip()]
    return lines[1] if len(lines) >= 2 else ""


def parse_bytes(text: str) -> int:
    """把 redis 的内存数值(1024 / 1M / 1G)解析成字节。"""
    text = (text or "").strip()
    if not text:
        return 0
    m = re.match(r"^(\d+)\s*([KMGT]?)B?$", text, re.IGNORECASE)
    if not m:
        try:
            return int(text)
        except ValueError:
            return 0
    value = int(m.group(1))
    unit = m.group(2).upper()
    factor = {"": 1, "K": 1024, "M": 1024 ** 2, "G": 1024 ** 3, "T": 1024 ** 4}[unit]
    return value * factor


def human_bytes(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f%s" % (n, unit) if unit != "B" else "%dB" % n
        n /= 1024.0
    return str(n)


class Report:
    """收集检查结果, 最后统一打印 + 决定退出码。"""

    def __init__(self):
        self.items = []

    def add(self, level: str, name: str, detail: str, advice: str = ""):
        self.items.append((level, name, detail, advice))

    def ok(self, name, detail, advice=""):
        self.add(PASS, name, detail, advice)

    def warn(self, name, detail, advice=""):
        self.add(WARN, name, detail, advice)

    def fail(self, name, detail, advice=""):
        self.add(FAIL, name, detail, advice)

    def print_all(self):
        print("=" * 72)
        print("Redis 部署前置检查")
        print("=" * 72)
        for level, name, detail, advice in self.items:
            print("%s %s" % (LEVEL_ICON[level], name))
            print("       %s" % detail)
            if advice:
                print("       → %s" % advice)
        print("-" * 72)
        fails = [i for i in self.items if i[0] == FAIL]
        warns = [i for i in self.items if i[0] == WARN]
        if fails:
            print("结论: %d 项阻断、%d 项警告 —— **不要开抢**, 先把 FAIL 项改掉。" % (len(fails), len(warns)))
        elif warns:
            print("结论: 全部通过, 但有 %d 项警告 —— 知会一下再开抢。" % len(warns))
        else:
            print("结论: 全部通过, 可以开抢。")
        return 1 if fails else 0


# ============================== 各项检查 ==============================

def check_memory(rep: Report, host: str, port: str):
    """内存上限与驱逐策略 —— 决定「位图会不会被悄悄丢掉」。"""
    maxmemory = parse_bytes(config_get(host, port, "maxmemory"))
    policy = config_get(host, port, "maxmemory-policy")
    mem_info = redis_cli(host, port, "INFO", "memory")
    used = 0
    for line in mem_info.splitlines():
        if line.startswith("used_memory:"):
            used = int(line.split(":")[1].strip() or 0)
            break

    if maxmemory <= 0:
        rep.fail("maxmemory 未设置",
                 "maxmemory = 0(不限内存)。位图没有上限保护, 一个越界 offset 就能把内存吃穿。",
                 "在 redis.conf 里设 maxmemory 512mb(按机器实际内存留 2~3 倍余量)")
    else:
        rep.ok("maxmemory 已设置", "maxmemory = %s" % human_bytes(maxmemory))
        if used and used > maxmemory * 0.8:
            rep.warn("内存余量偏紧",
                     "used %s / limit %s —— 已用超过 80%%, 触顶后会进驱逐流程。"
                     % (human_bytes(used), human_bytes(maxmemory)),
                     "调大 maxmemory, 或先跑 tools/reconcile_seat_bitmap.py 看位图有没有撑大的")
        else:
            rep.ok("内存余量充足", "used %s / limit %s" % (human_bytes(used), human_bytes(maxmemory)))

    if policy == "noeviction":
        rep.ok("maxmemory-policy = noeviction", "不会驱逐键 —— 位图不会被悄悄丢掉")
    else:
        rep.fail("maxmemory-policy = %s" % policy,
                 "Redis 会驱逐键。**锁定位图被驱逐 = 那批座位立刻回到可选池 = 同一座位卖两次。**",
                 "改成 maxmemory-policy noeviction")


def check_persistence(rep: Report, host: str, port: str):
    """持久化 —— 位图丢了能从 DB 重建, 但写停摆会直接打断抢票。"""
    appendonly = config_get(host, port, "appendonly")
    save = config_get(host, port, "save")
    stop_writes = config_get(host, port, "stop-writes-on-bgsave-error")

    if appendonly == "yes" or save:
        rep.ok("持久化已开启", "appendonly=%s, save=%s" % (appendonly or "no", save or ""))
    else:
        rep.warn("持久化未开启",
                 "appendonly=no 且没有 RDB 快照。位图丢了能靠 SeatBitmapGuard 从 DB 重建"
                 "(所以不是致命), 但重启后所有会话要重新冷启动。",
                 "开 RDB 快照即可(appendonly 视部署需要)")

    if stop_writes == "no":
        rep.ok("stop-writes-on-bgsave-error = no",
               "后台保存失败时不会把 Redis 写成只读")
    else:
        rep.warn("stop-writes-on-bgsave-error = yes",
                 "后台保存失败时 Redis 会停止接受写入 —— 抢票中途整站卡住。",
                 "改成 stop-writes-on-bgsave-error no, 并盯住 RDB 磁盘")


def check_clients(rep: Report, host: str, port: str):
    """连接数 —— 抢票高峰前端 + WS 各自占一条连接。"""
    maxclients = config_get(host, port, "maxclients")
    info = redis_cli(host, port, "INFO", "clients")
    connected = 0
    for line in info.splitlines():
        if line.startswith("connected_clients:"):
            connected = int(line.split(":")[1].strip() or 0)
    if not maxclients or maxclients == "0":
        rep.warn("maxclients 未设置", "客户端连接数无上限")
    else:
        rep.ok("连接数", "connected=%d / maxclients=%s" % (connected, maxclients))
        if connected > int(maxclients) * 0.8:
            rep.warn("连接数偏高", "已用超过 80%%, 抢票高峰可能连不上")


def check_bitmap_keys(rep: Report, host: str, port: str):
    """位图键体检 —— TTL 与体积。"""
    pattern = "%ssession:*:lock" % REDIS_PREFIX
    out = redis_cli(host, port, "--scan", "--pattern", pattern)
    keys = [l.strip() for l in out.splitlines() if l.strip()]

    with_ttl = []
    oversized = []
    total_bytes = 0
    for key in keys:
        ttl = redis_cli(host, port, "TTL", key)
        if ttl and ttl != "-1":
            with_ttl.append((key, ttl))
        size = int(redis_cli(host, port, "STRLEN", key) or 0)
        total_bytes += size
        # 240 座 / 140 座 → 30 / 18 字节; 超过 1KB 基本就是被越界位撑过
        if size > 1024:
            oversized.append((key, size))

    if not keys:
        rep.warn("没有位图键", "pattern=%s 扫不到任何键(服务没起? 或前缀不对?)" % pattern)
        return

    if with_ttl:
        rep.fail("位图键上挂了 TTL",
                 "%d 个位图键带过期时间, 例如 %s (剩余 %ss)。"
                 "键一到点就没了 = 那批座位凭空释放 = 可超卖。"
                 % (len(with_ttl), with_ttl[0][0], with_ttl[0][1]),
                 "去掉这些键的 TTL; 过期回收只允许发生在**已结束**的场次上")
    else:
        rep.ok("位图键没有 TTL", "%d 个锁定位图键, 均无过期时间" % len(keys))

    if oversized:
        rep.fail("有被撑大的位图",
                 "%d 个位图超过 1KB, 例如 %s (%d 字节)。"
                 "越界座位索引会把位图按 offset 撑大(offset=100000 → 12501 字节)。"
                 % (len(oversized), oversized[0][0], oversized[0][1]),
                 "跑 python tools/reconcile_seat_bitmap.py --purge-ghost-orders --fix")
    else:
        rep.ok("位图体积正常", "%d 个锁定位图, 合计 %s" % (len(keys), human_bytes(total_bytes)))


def check_lua(rep: Report, host: str, port: str):
    """Lua 脚本能力 —— 锁座/退票全靠它, 少了就等于没有防超卖。"""
    try:
        out = redis_cli(host, port, "EVAL", "return 1", "0")
        if out.strip() == "1":
            rep.ok("EVAL 可用", "Lua 脚本可用 —— 锁座/退票脚本能正常跑")
        else:
            rep.fail("EVAL 返回异常", "EVAL 'return 1' 返回了 %r" % out)
    except Exception as e:  # noqa: BLE001
        rep.fail("EVAL 不可用", "执行 Lua 脚本失败: %s" % e,
                 "检查 redis.conf 是否禁用了 scripting / rename-command")


def main() -> int:
    ap = argparse.ArgumentParser(description="Redis 部署前置检查(只读)")
    ap.add_argument("--host", default=REDIS_HOST)
    ap.add_argument("--port", default=REDIS_PORT)
    args = ap.parse_args()

    try:
        pong = redis_cli(args.host, args.port, "PING")
    except Exception as e:  # noqa: BLE001
        print("连不上 Redis %s:%s —— %s" % (args.host, args.port, e), file=sys.stderr)
        return 2
    if "PONG" not in pong:
        print("Redis 没回 PING: %r" % pong, file=sys.stderr)
        return 2

    print("目标: %s:%s  (前缀 %s)" % (args.host, args.port, REDIS_PREFIX))
    rep = Report()
    for check in (check_memory, check_persistence, check_clients,
                  check_bitmap_keys, check_lua):
        try:
            check(rep, args.host, args.port)
        except Exception as e:  # noqa: BLE001
            rep.warn(check.__name__, "检查过程中出错: %s" % e)
    return rep.print_all()


if __name__ == "__main__":
    sys.exit(main())
