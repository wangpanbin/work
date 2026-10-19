#!/usr/bin/env python3
"""座位位图对账 —— Redis 位图 vs DB 订单状态。

背景:
  座位占用的权威在 DB(`order` + `order_item`), Redis 位图只是加速层。
  两者一旦漂移, 就会出现「明明订单已取消, 座位却还锁着」(座位被永久占死,
  选座页上永远灰着) 或者反过来「订单还在, 位图却空了」(可超卖)。
  2026-10-19 的联调报告 F5 就是前一种: 场次 1130202003 的 1排1座/1排2座
  仍被位图占用, 对应订单早已取消。

本脚本做三件事:
  1. 从 DB 算出每个场次「应该被占」的座位(PENDING_PAY ∪ PAID);
  2. 从 Redis 读出每个场次实际占用的座位(lock 位图 ∪ sold 位图);
  3. 比对两者, 报告两类偏差:
       泄漏(leak)  —— 位图占用着, 但 DB 里没有任何有效订单占它。安全, 可清。
       缺失(miss)  —— DB 说该占, 位图却是空的。危险(可超卖), 只报告不自动修。

判定口径:
  * 「应该被占」= status IN (0 待支付, 1 已支付) 的订单座位。
    status 2 已取消 / 4 已退款 的座位必须回到可选池。
  * sold 位图单独对账: 只有已支付订单的座位才该是 sold=1。
    退款中(3)仍算占用(退票流程会连 sold+lock 一起清)。
  * lock 位图与 sold 位图的重叠不报错 —— confirm_seat.lua 会清 lock 置 sold,
    而位图重建脚本会把已支付座位同时写进两张位图, 两种形态都合法。

依赖: 仅 Python 3.9+ 标准库(走 mysql.exe / redis-cli 子进程, 不引三方包)。

用法:
    python tools/reconcile_seat_bitmap.py                 # 只报告(默认 dry-run)
    python tools/reconcile_seat_bitmap.py --session 1130202003
    python tools/reconcile_seat_bitmap.py --fix           # 清掉泄漏位(按 DB 权威)
    python tools/reconcile_seat_bitmap.py --fix --dry-run # 只列将要清的位, 不动手

退出码:
    0 = 无偏差; 1 = 有泄漏或缺失; 2 = 执行出错。
"""
from __future__ import annotations

import argparse
import io
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEV_YML = REPO_ROOT / "cinema-server" / "src" / "main" / "resources" / "application-dev.yml"

REDIS_HOST = "127.0.0.1"
REDIS_PORT = "6379"

#: 订单状态 -> 该状态下座位是否仍应被占用
OCCUPIED_STATUS = (0, 1)   # 0 待支付, 1 已支付
SOLD_STATUS = (1,)         # 1 已支付


# ============================== 基础设施 ==============================

def read_db_config() -> dict:
    """从 application-dev.yml 读数据库连接(该文件被 gitignore, 不入库)。"""
    with io.open(DEV_YML, encoding="utf-8", newline="") as f:
        raw = f.read()

    def pick(key: str) -> str:
        m = re.search(r"(?m)^\s*%s\s*:\s*(.+)$" % key, raw)
        return m.group(1).strip() if m else ""

    url = pick("url")
    db = "cinema"
    # jdbc:mysql://host:3306/cinema?params —— 取 host 之后的第一段路径
    m = re.search(r"//[^/]+/([^/?]+)", url)
    if m:
        db = m.group(1)
    return {"user": pick("username"), "password": pick("password"), "db": db}


def mysql(sql: str, cfg: dict) -> str:
    """执行 SQL, 返回 tab 分隔的原始输出(带表头)。"""
    cmd = ["mysql", "-h", "127.0.0.1", "-u", cfg["user"], "-p" + cfg["password"],
           "-D", cfg["db"], "-B", "-e", sql]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    if p.returncode != 0:
        raise RuntimeError("mysql 失败: " + (p.stderr or "").strip())
    return p.stdout


def redis_key_bits(key: str) -> set:
    """读一个 Redis 位图键, 返回值为 1 的位偏移集合。

    位序: **字节内 MSB 优先**(与 GETBIT 一致)。实测: 字节 0xC0 的 GETBIT 0 与
    GETBIT 1 都是 1 —— 即字节的**最高位**算第 0 位。Redis 把位图当成一个大端
    位序列, 前端 `utils/bitmap.ts` 的 `bit = 7 - (i & 7)` 也是同一套编码。

    ⚠️ redis-cli 取值有两个坑, 都要绕开:
      ① `--no-raw` 给的是**转义文本**(带引号与 \\n 转义), 不是原始字节 ——
         照着解会凭空多出上百个位;
      ② `--raw` 给的是原始字节, 但 redis-cli **会补一个行尾换行** ——
         那个 0x0A 会多出 2 个位(实测 145/147)。
    所以用 STRLEN 取真实字节数, 再从 raw 输出里截取这么多字节。
    """
    size = int(redis_cli("STRLEN", key) or 0)
    if size == 0:
        return set()
    raw = redis_cli("--raw", "GET", key, binary=True)
    if raw in (b"", b"(nil)"):
        return set()
    raw = raw[:size]          # 去掉 redis-cli 补的行尾换行
    bits = set()
    for byte_index, byte in enumerate(raw):
        if byte == 0:
            continue
        base = byte_index * 8
        for j in range(8):
            if byte & (1 << (7 - j)):     # MSB 优先: 字节最高位是第 0 位
                bits.add(base + j)
    return bits


def redis_cli(*args, binary: bool = False):
    """跑一条 redis-cli 命令。binary=True 时返回原始字节。"""
    p = subprocess.run(["redis-cli", "-h", REDIS_HOST, "-p", REDIS_PORT, *args],
                       capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("redis %s 失败: %s" % (" ".join(args), p.stderr.decode("utf-8", "replace")))
    if binary:
        return p.stdout
    return p.stdout.decode("utf-8", "replace").strip()


def redis_setbit(key: str, offset: int, value: int) -> None:
    p = subprocess.run(["redis-cli", "-h", REDIS_HOST, "-p", REDIS_PORT,
                        "SETBIT", key, str(offset), str(value)],
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    if p.returncode != 0:
        raise RuntimeError("redis SETBIT %s %d 失败: %s" % (key, offset, p.stderr))


def redis_keys(pattern: str) -> list:
    p = subprocess.run(["redis-cli", "-h", REDIS_HOST, "-p", REDIS_PORT, "--scan", "--pattern", pattern],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        raise RuntimeError("redis --scan 失败: " + (p.stderr or "").strip())
    return [line.strip() for line in p.stdout.splitlines() if line.strip()]


# ============================== 取数 ==============================

def collect_db_state() -> dict:
    """从 DB 汇总每个场次的座位占用期望值。

    返回 {session_id: {"occupied": set, "sold": set, "orders": [...]}}。
    """
    rows = mysql(
        "SELECT o.session_id, o.order_no, o.status, oi.seat_index "
        "FROM `order` o JOIN order_item oi ON oi.order_id = o.id "
        "ORDER BY o.session_id, o.order_no, oi.seat_index;", read_db_config())
    lines = [l for l in rows.splitlines() if l.strip()]
    if not lines:
        return {}
    state: dict = {}
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        sid, order_no, status, seat = parts[0], parts[1], int(parts[2]), int(parts[3])
        entry = state.setdefault(sid, {"occupied": set(), "sold": set(), "orders": []})
        if status in OCCUPIED_STATUS:
            entry["occupied"].add(seat)
        if status in SOLD_STATUS:
            entry["sold"].add(seat)
        entry["orders"].append((order_no, status, seat))
    return state


# ============================== 对账 ==============================

def reconcile(session_filter: str | None, include_orphan: bool = False) -> list:
    """比对 DB 与 Redis, 返回偏差列表。

    默认**只对账有 DB 订单的场次** —— 位图有位而 DB 一单没有的场次是压测/探针脚本
    留下的污染(本仓 test/ 下多个脚本直接 SETBIT), 混进来会把真正的偏差淹掉。
    想知道那些场次时加 --include-orphan。
    """
    db_state = collect_db_state()
    lock_keys = redis_keys("cinema:session:*:lock")

    findings = []
    for key in sorted(lock_keys):
        m = re.match(r"cinema:session:(\d+):lock$", key)
        if not m:
            continue
        sid = m.group(1)
        if session_filter and sid != session_filter:
            continue
        entry = db_state.get(sid)
        if entry is None and not include_orphan:
            continue
        lock_bits = redis_key_bits(key)
        sold_bits = redis_key_bits("cinema:session:%s:sold" % sid)
        expected_occupied = entry["occupied"] if entry else set()
        expected_sold = entry["sold"] if entry else set()
        actual_occupied = lock_bits | sold_bits

        leak = sorted(actual_occupied - expected_occupied)
        miss = sorted(expected_occupied - actual_occupied)
        sold_leak = sorted(sold_bits - expected_sold)
        if leak or miss or sold_leak:
            findings.append({
                "session_id": sid,
                "leak": leak,
                "miss": miss,
                "sold_leak": sold_leak,
                "lock_key": key,
                "has_orders": entry is not None,
            })
    return findings


def _fmt(seats: list, limit: int = 12) -> str:
    """座位列表的紧凑展示 —— 偏差可能有几百个, 全量打印会淹掉输出。"""
    if not seats:
        return "-"
    head = ", ".join(str(s) for s in seats[:limit])
    return head + (" ... (共 %d 个)" % len(seats) if len(seats) > limit else "")


def print_findings(findings: list) -> None:
    if not findings:
        print("✓ 位图与 DB 订单状态一致, 无偏差。")
        return
    print("发现 %d 个场次存在偏差:" % len(findings))
    for f in findings:
        tag = "" if f["has_orders"] else "  [孤儿位图: DB 无任何订单, 多为压测脚本污染]"
        print("  场次 %s%s" % (f["session_id"], tag))
        if f["leak"]:
            print("    泄漏(位图占着, DB 无有效订单): %s" % _fmt(f["leak"]))
        if f["miss"]:
            print("    缺失(DB 说该占, 位图是空的):   %s" % _fmt(f["miss"]))
        if f["sold_leak"]:
            print("    sold 残留(非已支付却标已售):   %s" % _fmt(f["sold_leak"]))


def apply_fix(findings: list, dry_run: bool) -> int:
    cleared = 0
    for f in findings:
        for seat in f["leak"]:
            # 泄漏位可能落在 lock 位图也可能落在 sold 位图, 两边都清一次是幂等的
            for key in ("cinema:session:%s:lock" % f["session_id"],
                        "cinema:session:%s:sold" % f["session_id"]):
                if dry_run:
                    print("  [dry-run] %s bit %d -> 0" % (key, seat))
                else:
                    redis_setbit(key, seat, 0)
            cleared += 1
    if not dry_run:
        print("已清理 %d 个泄漏座位位。" % cleared)
    else:
        print("[dry-run] 共将清理 %d 个泄漏座位位, 未实际写入。" % cleared)
    return cleared


def main() -> int:
    ap = argparse.ArgumentParser(description="座位位图与 DB 订单状态对账")
    ap.add_argument("--session", help="只对账指定场次 ID")
    ap.add_argument("--include-orphan", action="store_true",
                    help="连同「DB 无任何订单但位图有位」的场次一起查(压测污染)")
    ap.add_argument("--fix", action="store_true", help="清掉泄漏位(默认只报告)")
    ap.add_argument("--dry-run", action="store_true", help="配合 --fix 使用: 只列不写")
    args = ap.parse_args()

    try:
        findings = reconcile(args.session, args.include_orphan)
    except Exception as e:  # noqa: BLE001
        print("对账失败: %s" % e, file=sys.stderr)
        return 2

    print_findings(findings)
    if args.fix and findings:
        apply_fix(findings, args.dry_run)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
