#!/usr/bin/env python3
"""座位位图对账 —— Redis 位图 vs DB 订单状态, 并清理「幽灵订单」。

背景:
  座位占用的权威在 DB(`order` + `order_item`), Redis 位图只是加速层。
  两者一旦漂移, 就会出现「明明订单已取消, 座位却还锁着」(座位被永久占死,
  选座页上永远灰着) 或者反过来「订单还在, 位图却空了」(可超卖)。
  2026-10-19 的联调报告 F5 就是前一种: 场次 1130202003 的 1排1座/1排2座
  仍被位图占用, 对应订单早已取消。

本脚本做两件事(两种模式, 互斥):

  【对账模式】(默认)
    1. 从 DB 算出每个场次「应该被占」的座位(PENDING_PAY ∪ PAID);
    2. 从 Redis 读出每个场次实际占用的座位(lock 位图 ∪ sold 位图);
    3. 比对两者, 报告两类偏差:
         泄漏(leak)  —— 位图占用着, 但 DB 里没有任何有效订单占它。安全, 可清。
         缺失(miss)  —— DB 说该占, 位图却是空的。危险(可超卖), 只报告不自动修。

  【幽灵订单模式】(--purge-ghost-orders)
    清理 P2-2 修复**之前**落库的历史脏单: 那些订单的 `order_item.seat_index`
    指向该影厅里根本不存在的座位(140 / 100000 / 1000000 …)。
    影响有两条:
      ① DB 侧 —— 订单座位数与金额被污染, 营收/上座率统计把幽灵座位算进去;
      ② Redis 侧 —— 位图按 offset 立即分配且**永不回收**, 一个 offset=100000
         的脏位就把位图从 18B 撑到 12501B(实测), `GET /seat-map` 的响应
         同步放大 61 倍。更麻烦的是: `SeatBitmapRebuilder` 会照着这些脏
         order_item 重建位图, 于是**每次重建都把位图重新撑大** ——
         只清 Redis 不清 DB, 等于白干。

判定口径:
  * 「应该被占」= status IN (0 待支付, 1 已支付) 的订单座位, 且**该座位在
    本影厅里真实存在**(越界的幽灵座位不算期望值, 否则清完还会被算成「缺失」)。
  * status 2 已取消 / 4 已退款 的座位必须回到可选池。
  * sold 位图单独对账: 只有已支付订单的座位才该是 sold=1。
    退款中(3)仍算占用(退票流程会连 sold+lock 一起清)。
  * lock 位图与 sold 位图的重叠不报错 —— confirm_seat.lua 会清 lock 置 sold,
    而位图重建脚本会把已支付座位同时写进两张位图, 两种形态都合法。

⚠️ 关于「清位」: Redis 的位图字符串**不会**因为高位被清零而自动收缩 ——
实测把 offset=100000 的位清零后, STRLEN 仍然是 12501。所以本脚本的位图清理
走 Lua 里的「DEL + 按合法位重建」(单脚本原子), 物理把体积降回来。
只用 SETBIT → 0 的话, 座位能选了, 但内存和响应体积的损失原封不动。

依赖: 仅 Python 3.9+ 标准库(走 mysql.exe / redis-cli 子进程, 不引三方包)。

用法:
    python tools/reconcile_seat_bitmap.py                 # 对账(只报告, 默认 dry-run)
    python tools/reconcile_seat_bitmap.py --session 1130202003
    python tools/reconcile_seat_bitmap.py --fix           # 清掉泄漏位(按 DB 权威)
    python tools/reconcile_seat_bitmap.py --fix --dry-run # 只列将要清的位, 不动手

    python tools/reconcile_seat_bitmap.py --purge-ghost-orders
    python tools/reconcile_seat_bitmap.py --purge-ghost-orders --fix
    python tools/reconcile_seat_bitmap.py --purge-ghost-orders --fix --dry-run

退出码:
    0 = 无偏差; 1 = 有泄漏/缺失/幽灵订单; 2 = 执行出错。
"""
from __future__ import annotations

import argparse
import io
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEV_YML = REPO_ROOT / "cinema-server" / "src" / "main" / "resources" / "application-dev.yml"

REDIS_HOST = os.environ.get("CINEMA_REDIS_HOST", "127.0.0.1")
REDIS_PORT = os.environ.get("CINEMA_REDIS_PORT", "6379")
#: Redis 键前缀 —— 环境变量可改, 便于在临时库/临时键空间里跑集成测试
REDIS_PREFIX = os.environ.get("CINEMA_REDIS_PREFIX", "cinema:")

#: 订单状态 -> 该状态下座位是否仍应被占用
OCCUPIED_STATUS = (0, 1)   # 0 待支付, 1 已支付
SOLD_STATUS = (1,)         # 1 已支付

#: 订单状态码(与后端 OrderStatus 一致)
STATUS_PENDING = 0     # 待支付
STATUS_PAID = 1        # 已支付
STATUS_CANCELLED = 2   # 已取消
STATUS_REFUNDING = 3   # 退款中
STATUS_REFUNDED = 4    # 已退款

#: 幽灵订单的处理策略
ACTION_PURGE = "purge"                          # 删越界明细 + 修正 seat_count / 金额
ACTION_PURGE_AND_CANCEL = "purge-and-cancel"    # 同上 + 整单置为已取消(无座位可留)
ACTION_PURGE_KEEP_AMOUNT = "purge-keep-amount"  # 只修正 seat_count; 金额是历史事实
ACTION_REPORT_ONLY = "report-only"              # 已支付: 只报告, 不自动改(退款是钱的决策)

#: 位图截断脚本 —— 原子地把位图重建成「只含合法位」的样子。
#:
#: 为什么不用 SETBIT → 0: Redis 的位图字符串不会因为清零高位而收缩
#: (实测 offset=100000 清零后 STRLEN 仍是 12501), 位图的内存与
#: `GET /seat-map` 的响应体积不会回落。为什么不用 SETRANGE 截断:
#: SETRANGE 传空串是**空操作**(实测返回原长度、长度不变), 截不掉。
#:
#: 所以只能 DEL 之后按合法位重建 —— 放在同一个 Lua 脚本里, 对外仍是原子的。
TRIM_BITMAP_LUA = """
local key = KEYS[1]
local seat_count = tonumber(ARGV[1])
local value = redis.call('GET', key)
if not value then return {0, 0, 0, 0} end
local before = #value
local keep_bytes = math.ceil(seat_count / 8)

-- 1) 数一数越界位(>= seat_count 且置 1 的位)
local out_count = 0
for byte_index = 0, before - 1 do
  local b = string.byte(value, byte_index + 1)
  if b ~= 0 then
    for j = 0, 7 do
      local off = byte_index * 8 + j
      if off >= seat_count and math.floor(b / 2^(7 - j)) % 2 == 1 then
        out_count = out_count + 1
      end
    end
  end
end

-- 2) 收拢合法位
local bits = {}
for byte_index = 0, math.min(before, keep_bytes) - 1 do
  local b = string.byte(value, byte_index + 1)
  if b ~= 0 then
    for j = 0, 7 do
      local off = byte_index * 8 + j
      if off >= seat_count then break end
      if math.floor(b / 2^(7 - j)) % 2 == 1 then bits[#bits + 1] = off end
    end
  end
end

-- 3) 需要动才动: DEL + 按合法位重建(脚本原子, 中间没有「位图为空」的窗口)
local truncated = 0
if out_count > 0 or before > keep_bytes then
  redis.call('DEL', key)
  for i = 1, #bits do redis.call('SETBIT', key, bits[i], 1) end
  truncated = 1
end
return {out_count, truncated, before, redis.call('STRLEN', key)}
"""


# ============================== 基础设施 ==============================

def read_db_config() -> dict:
    """从 application-dev.yml 读数据库连接(该文件被 gitignore, 不入库)。

    环境变量优先 —— 集成测试要用临时库跑, 不该去动开发库:
      CINEMA_DB_HOST / CINEMA_DB_PORT / CINEMA_DB_NAME / CINEMA_DB_USER / CINEMA_DB_PASSWORD
    """
    raw = ""
    if DEV_YML.exists():
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
    return {
        "host": os.environ.get("CINEMA_DB_HOST", "127.0.0.1"),
        "port": os.environ.get("CINEMA_DB_PORT", "3306"),
        "user": os.environ.get("CINEMA_DB_USER", pick("username")),
        "password": os.environ.get("CINEMA_DB_PASSWORD", pick("password")),
        "db": os.environ.get("CINEMA_DB_NAME", db),
    }


def _mysql_cmd(cfg: dict, sql: str) -> list:
    return ["mysql", "-h", cfg.get("host", "127.0.0.1"), "-P", str(cfg.get("port", "3306")),
            "-u", cfg["user"], "-p" + cfg["password"],
            "-D", cfg["db"], "-B", "-e", sql]


def mysql(sql: str, cfg: dict) -> str:
    """执行 SQL, 返回 tab 分隔的原始输出(带表头)。"""
    p = subprocess.run(_mysql_cmd(cfg, sql), capture_output=True, text=True, encoding="utf-8",
                       errors="replace")
    if p.returncode != 0:
        raise RuntimeError("mysql 失败: " + (p.stderr or "").strip())
    return p.stdout


def mysql_exec(sql: str, cfg: dict) -> int:
    """执行写操作, 返回受影响行数。

    末尾追加 ``SELECT ROW_COUNT()`` 取真实影响行数 —— mysql 的 "Rows matched"
    提示只在 verbose 模式打印, 批处理模式下不可靠。
    """
    out = mysql(sql + " SELECT ROW_COUNT();", cfg)
    lines = [l for l in out.splitlines() if l.strip()]
    if not lines:
        return 0
    try:
        return int(lines[-1].strip())
    except ValueError:
        return 0


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
    p = subprocess.run(["redis-cli", "-h", REDIS_HOST, "-p", REDIS_PORT, *[str(a) for a in args]],
                       capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("redis %s 失败: %s" % (" ".join(str(a) for a in args),
                                                 p.stderr.decode("utf-8", "replace")))
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


def trim_bitmap(key: str, seat_count: int) -> dict:
    """把位图截断到 seatCount 位(原子 DEL + 按合法位重建)。

    返回 {"out_bits": 越界位个数, "truncated": 是否重建过,
          "bytes_before": 重建前字节数, "bytes_after": 重建后字节数}。
    """
    raw = redis_cli("--raw", "EVAL", TRIM_BITMAP_LUA, "1", key, str(seat_count))
    nums = []
    for line in raw.splitlines():
        line = line.strip()
        if line.lstrip("-").isdigit():
            nums.append(int(line))
    while len(nums) < 4:
        nums.append(0)
    return {"out_bits": nums[0], "truncated": nums[1],
            "bytes_before": nums[2], "bytes_after": nums[3]}


# ============================== 取数 ==============================

def collect_db_state() -> dict:
    """从 DB 汇总每个场次的座位占用期望值。

    返回 {session_id: {"occupied": set, "sold": set, "orders": [...]}}。

    ⚠️ 只统计**该影厅里真实存在**的座位 —— 越界的幽灵座位(seat_index 不在
    `seat` 表里)不是「期望占用」, 否则清掉位图之后对账会把它们报成「缺失」,
    把一次成功的清理说成一次危险的漂移。
    """
    rows = mysql(
        "SELECT o.session_id, o.order_no, o.status, oi.seat_index "
        "FROM `order` o "
        "JOIN order_item oi ON oi.order_id = o.id "
        "JOIN `session` s ON s.id = o.session_id "
        "JOIN seat st ON st.hall_id = s.hall_id AND st.seat_index = oi.seat_index "
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


def collect_order_seats() -> list:
    """取全部订单的座位明细, 并标出每个座位在该影厅里是否存在。

    返回扁平的行元组, 交给 {@link group_order_rows} 分组。

    字段: (order_id, order_no, session_id, status, seat_count, total_amount,
           hall_id, hall_seat_count, seat_index, price, seat_exists)
    """
    rows = mysql(
        "SELECT o.id, o.order_no, o.session_id, o.status, o.seat_count, o.total_amount, "
        "       s.hall_id, h.seat_count, oi.seat_index, oi.price, "
        "       IF(st.id IS NULL, 0, 1) "
        "FROM `order` o "
        "JOIN order_item oi ON oi.order_id = o.id "
        "JOIN `session` s ON s.id = o.session_id "
        "JOIN hall h ON h.id = s.hall_id "
        "LEFT JOIN seat st ON st.hall_id = s.hall_id AND st.seat_index = oi.seat_index "
        "ORDER BY o.session_id, o.order_no, oi.seat_index;", read_db_config())
    lines = [l for l in rows.splitlines() if l.strip()]
    out = []
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) < 11:
            continue
        out.append(tuple(parts))
    return out


def collect_session_seat_counts() -> dict:
    """{session_id: seat_count} —— 场次所在影厅的座位数, 即位图的合法上界。

    取 ``hall.seat_count``, 与后端一致(``OrderCore#requireSeatsInRange`` 与
    ``SeatService`` 用的都是它); ``seat`` 表的逐行存在性由另一条查询负责。
    """
    rows = mysql(
        "SELECT s.id, h.seat_count FROM `session` s JOIN hall h ON h.id = s.hall_id;",
        read_db_config())
    out: dict = {}
    for line in rows.splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) >= 2:
            out[parts[0]] = int(parts[1] or 0)
    return out


def find_out_of_range_bitmaps(session_seat_counts: dict,
                              session_filter: str | None = None) -> list:
    """扫全部场次的位图, 找出含**越界位**的键。

    越界位 = 位偏移 >= 该影厅座位数 —— 那个位置根本没有座位, 位图上的它
    既锁不住也卖不掉, 只会把位图字符串撑大(实测 offset=100000 → 12501B)。
    """
    findings = []
    for kind in ("lock", "sold"):
        pattern = "%ssession:*:%s" % (REDIS_PREFIX, kind)
        for key in redis_keys(pattern):
            m = re.match(r"%ssession:(\d+):%s$" % (re.escape(REDIS_PREFIX), kind), key)
            if not m:
                continue
            sid = m.group(1)
            if session_filter and sid != session_filter:
                continue
            seat_count = session_seat_counts.get(sid)
            if not seat_count:
                # 影厅座位数查不到(影厅被删/数据异常)→ 不猜, 跳过
                continue
            bits = redis_key_bits(key)
            out_of_range = sorted(b for b in bits if b >= seat_count)
            if out_of_range:
                findings.append({
                    "key": key,
                    "session_id": sid,
                    "seat_count": seat_count,
                    "out_of_range": out_of_range,
                    "bytes": int(redis_cli("STRLEN", key) or 0),
                })
    return findings


def group_order_rows(rows: list) -> dict:
    """扁平的订单座位明细 → {order_id: 订单信息}。

    纯函数 —— 单测直接喂行元组, 不连数据库。
    """
    orders: dict = {}
    for r in rows:
        oid = r[0]
        o = orders.setdefault(oid, {
            "order_id": oid,
            "order_no": r[1],
            "session_id": r[2],
            "status": int(r[3]),
            "seat_count": int(r[4] or 0),
            "total_amount": r[5],
            "hall_id": r[6],
            "hall_seat_count": int(r[7] or 0),
            "seats": [],
        })
        o["seats"].append({
            "seat_index": int(r[8]),
            "price": r[9],
            "exists": str(r[10]).strip() == "1",
        })
    return orders


def pick_ghost_orders(orders: dict) -> list:
    """从分组结果里挑出幽灵订单 —— 含「该影厅里不存在的座位」的订单。

    纯函数。返回的每条带 ``invalid_seats``(越界座位) 与 ``valid_seats``(真实座位)。
    """
    ghosts = []
    for o in orders.values():
        invalid = [s["seat_index"] for s in o["seats"] if not s["exists"]]
        if not invalid:
            continue
        item = dict(o)
        item["invalid_seats"] = invalid
        item["valid_seats"] = [s for s in o["seats"] if s["exists"]]
        ghosts.append(item)
    return ghosts


def plan_ghost_action(status: int, valid_count: int) -> str:
    """一张幽灵订单该怎么处理 —— 纯函数, 单测直接覆盖。

    分档的理由:
      * 已支付 → **只报告**。退款是钱的决策, 脚本不越权;
      * 退款中/已退款 → 金额是历史事实, 只修座位数;
      * 待支付且一张真实座位都不剩 → 整单没有意义, 置为已取消;
      * 其余 → 删越界明细, 按剩余明细修正座位数与金额。
    """
    if status == STATUS_PAID:
        return ACTION_REPORT_ONLY
    if status in (STATUS_REFUNDING, STATUS_REFUNDED):
        return ACTION_PURGE_KEEP_AMOUNT
    if valid_count == 0:
        return ACTION_PURGE_AND_CANCEL
    return ACTION_PURGE


# ============================== 对账 ==============================

def reconcile(session_filter: str | None, include_orphan: bool = False) -> list:
    """比对 DB 与 Redis, 返回偏差列表。

    默认**只对账有 DB 订单的场次** —— 位图有位而 DB 一单没有的场次是压测/探针脚本
    留下的污染(本仓 test/ 下多个脚本直接 SETBIT), 混进来会把真正的偏差淹掉。
    想知道那些场次时加 --include-orphan。
    """
    db_state = collect_db_state()
    lock_keys = redis_keys("%ssession:*:lock" % REDIS_PREFIX)

    findings = []
    for key in sorted(lock_keys):
        m = re.match(r"%ssession:(\d+):lock$" % re.escape(REDIS_PREFIX), key)
        if not m:
            continue
        sid = m.group(1)
        if session_filter and sid != session_filter:
            continue
        entry = db_state.get(sid)
        if entry is None and not include_orphan:
            continue
        lock_bits = redis_key_bits(key)
        sold_bits = redis_key_bits("%ssession:%s:sold" % (REDIS_PREFIX, sid))
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
            for key in ("%ssession:%s:lock" % (REDIS_PREFIX, f["session_id"]),
                        "%ssession:%s:sold" % (REDIS_PREFIX, f["session_id"])):
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


# ============================== 幽灵订单 ==============================

_STATUS_LABEL = {
    STATUS_PENDING: "待支付",
    STATUS_PAID: "已支付",
    STATUS_CANCELLED: "已取消",
    STATUS_REFUNDING: "退款中",
    STATUS_REFUNDED: "已退款",
}

_ACTION_LABEL = {
    ACTION_PURGE: "删越界明细 + 修正座位数/金额",
    ACTION_PURGE_AND_CANCEL: "删越界明细 + 整单置为已取消",
    ACTION_PURGE_KEEP_AMOUNT: "删越界明细 + 只修正座位数(金额是历史事实)",
    ACTION_REPORT_ONLY: "**只报告, 不自动改**(已支付, 退款是钱的决策)",
}


def print_ghost_report(ghosts: list, bitmaps: list) -> None:
    """打印幽灵订单 + 越界位图清单。"""
    if not ghosts:
        print("✓ 未发现幽灵订单(所有订单座位都在本影厅的真实座位范围内)。")
    else:
        print("发现 %d 张幽灵订单(含本影厅里不存在的座位):" % len(ghosts))
        for g in ghosts:
            action = plan_ghost_action(g["status"], len(g["valid_seats"]))
            print("  订单 %s  场次 %s  影厅 %s  状态 %s  金额 %s"
                  % (g["order_no"], g["session_id"], g["hall_id"],
                     _STATUS_LABEL.get(g["status"], g["status"]), g["total_amount"]))
            print("    越界座位: %s   (本影厅座位数 %s)"
                  % (_fmt(g["invalid_seats"]), g["hall_seat_count"]))
            print("    真实座位: %s" % _fmt([s["seat_index"] for s in g["valid_seats"]]))
            print("    处理方式: %s" % _ACTION_LABEL[action])

    if not bitmaps:
        print()
        print("✓ 未发现含越界位的位图。")
        return
    print()
    print("发现 %d 张含越界位的位图(越界位 = 位偏移 >= 该影厅座位数):" % len(bitmaps))
    for b in bitmaps:
        print("  %s  场次 %s  座位数 %s  越界位 %s  当前体积 %dB"
              % (b["key"], b["session_id"], b["seat_count"],
                 _fmt(b["out_of_range"]), b["bytes"]))


def purge_ghost_orders(ghosts: list, bitmaps: list, dry_run: bool) -> dict:
    """清理幽灵订单: DB 侧删越界明细 + 修订单字段; Redis 侧把位图截断回合法范围。

    返回本次的统计: {"orders": 处理了几张, "items": 删了几条明细,
                     "bitmaps": 动了几张位图, "bytes_saved": 位图回收了多少字节}。
    """
    cfg = read_db_config()
    stats = {"orders": 0, "items": 0, "bitmaps": 0, "bytes_saved": 0}

    for g in ghosts:
        action = plan_ghost_action(g["status"], len(g["valid_seats"]))
        oid = int(g["order_id"])
        invalid = ",".join(str(s) for s in g["invalid_seats"])
        if dry_run:
            print("  [dry-run] 订单 %s: 删 %d 条越界明细, 动作=%s"
                  % (g["order_no"], len(g["invalid_seats"]), action))
            stats["orders"] += 1
            stats["items"] += len(g["invalid_seats"])
            continue

        if action == ACTION_REPORT_ONLY:
            # 已支付 → DB 不动(退款是钱的决策), 但位图侧的越界位照清:
            # 那个位置根本没有座位, 留着既锁不住也卖不掉, 只会撑大位图。
            print("  订单 %s: 已支付, DB 侧跳过自动处理(需人工决定退款)" % g["order_no"])
            continue

        # ① 删越界明细 —— 这一步是根因: 只要这些行还在,
        #    SeatBitmapRebuilder 下次重建就会照着它们重新 SETBIT, 位图又撑大。
        n = mysql_exec("DELETE FROM order_item WHERE order_id = %d AND seat_index IN (%s);"
                       % (oid, invalid), cfg)
        stats["items"] += n
        stats["orders"] += 1

        # ② 修订单字段
        if action == ACTION_PURGE_AND_CANCEL:
            mysql_exec("UPDATE `order` SET status = %d, seat_count = 0, total_amount = 0 "
                       "WHERE id = %d AND status = %d;" % (STATUS_CANCELLED, oid, STATUS_PENDING), cfg)
        elif action == ACTION_PURGE:
            mysql_exec(
                "UPDATE `order` SET "
                "  seat_count = (SELECT COUNT(*) FROM order_item oi WHERE oi.order_id = `order`.id), "
                "  total_amount = (SELECT COALESCE(SUM(oi.price), 0) FROM order_item oi WHERE oi.order_id = `order`.id) "
                "WHERE id = %d;" % oid, cfg)
        else:  # ACTION_PURGE_KEEP_AMOUNT
            mysql_exec(
                "UPDATE `order` SET "
                "  seat_count = (SELECT COUNT(*) FROM order_item oi WHERE oi.order_id = `order`.id) "
                "WHERE id = %d;" % oid, cfg)

    # ③ Redis 侧: 把越界位图截断回合法范围(原子 DEL + 重建, 体积真的降回来)
    for b in bitmaps:
        key, seat_count = b["key"], b["seat_count"]
        if dry_run:
            print("  [dry-run] %s: %d 个越界位, 截断到 %d 位(当前 %dB)"
                  % (key, len(b["out_of_range"]), seat_count, b["bytes"]))
            continue
        res = trim_bitmap(key, seat_count)
        if res["out_bits"] or res["truncated"]:
            print("  %s: 清掉 %d 个越界位, 体积 %dB -> %dB"
                  % (key, res["out_bits"], res["bytes_before"], res["bytes_after"]))
            stats["bytes_saved"] += max(0, res["bytes_before"] - res["bytes_after"])
            stats["bitmaps"] += 1
    return stats


def run_ghost_mode(apply_changes: bool, dry_run: bool, session_filter: str | None = None) -> int:
    """--purge-ghost-orders 模式的入口。

    ``apply_changes`` = 传了 --fix; ``dry_run`` = 传了 --dry-run(优先级更高)。
    """
    try:
        ghosts = pick_ghost_orders(group_order_rows(collect_order_seats()))
        seat_counts = collect_session_seat_counts()
        bitmaps = find_out_of_range_bitmaps(seat_counts, session_filter)
    except Exception as e:  # noqa: BLE001
        print("查询失败: %s" % e, file=sys.stderr)
        return 2

    print_ghost_report(ghosts, bitmaps)
    if not ghosts and not bitmaps:
        return 0

    print()
    if not apply_changes:
        print("加 --fix 才会真正动手(默认只报告)。")
        return 1

    stats = purge_ghost_orders(ghosts, bitmaps, dry_run=dry_run)
    print()
    if dry_run:
        print("[dry-run] 共将处理 %d 张幽灵订单(删 %d 条越界明细)、%d 张越界位图, 未实际写入。"
              % (stats["orders"], stats["items"], len(bitmaps)))
        return 1
    print("已处理 %d 张幽灵订单, 删除 %d 条越界明细, %d 张位图回收 %d 字节。"
          % (stats["orders"], stats["items"], stats["bitmaps"], stats["bytes_saved"]))
    return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="座位位图与 DB 订单状态对账 / 幽灵订单清理")
    ap.add_argument("--session", help="只对账指定场次 ID")
    ap.add_argument("--include-orphan", action="store_true",
                    help="连同「DB 无任何订单但位图有位」的场次一起查(压测污染)")
    ap.add_argument("--fix", action="store_true", help="清掉泄漏位(默认只报告)")
    ap.add_argument("--dry-run", action="store_true", help="配合 --fix 使用: 只列不写")
    ap.add_argument("--purge-ghost-orders", action="store_true",
                    help="幽灵订单模式: 查并(配合 --fix)清理 seat_index 越界的历史脏单"
                         "与随之撑大的位图。与对账模式互斥。")
    args = ap.parse_args()

    if args.purge_ghost_orders:
        # 幽灵订单模式: 只跑清理, 不跑对账 —— 两种关注点混在一份输出里会看不清。
        # --dry-run 的优先级高于 --fix: 「--fix --dry-run」= 只列不写。
        return run_ghost_mode(apply_changes=args.fix, dry_run=args.dry_run,
                              session_filter=args.session)

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
