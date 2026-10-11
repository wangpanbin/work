#!/usr/bin/env python3
"""补种演示场次 —— 今天起 N 天(默认 3 天)的电影排片。

背景:
  cinema-web/src/views/MovieDetail.vue 的日期选择器只提供
  "今天 / 明天 / 后天" 三个选项(dateOptions 用 dayjs().add(0|1|2,'day')),
  且 selectedDate 默认选中明天。演示前若这三天没有场次,
  点进任何影片都是 el-empty "暂无场次", 核心秒杀选座链路无法演示。
  本脚本把这几天补齐。

为什么不能重跑 sql/04_extra_demo_data.sql:
  它的确定性 ID = movie*1e7 + hall*1e5 + d*100 + s, 其中 d 是**相对天数**,
  不含绝对日期。跑过一次后各 d 都被占用; 隔天重跑会算出同一批 ID,
  被 INSERT IGNORE 全部丢弃 —— 脚本报成功, 实际一行没插。这是最危险的
  失败模式: 执行时看不出问题, 到演示当天才暴露。
  (本脚本是 sql/05_refresh_sessions_for_demo.sql 的 Python 等价物,
   额外支持 --days / --dry-run / --reset)

本脚本的 ID 规则(绝对日期编码, 真幂等):
    id = movie_id * 1e7 + hall_id * 1e5 + DAYOFMONTH * 100 + slot
  跨天执行永远不撞; 同一天重跑 ID 完全相同, INSERT IGNORE 全部跳过。

依赖: 仅 Python 3.9+ 标准库。走 mysql.exe 子进程, 不引 pymysql/SQLAlchemy,
      与 tests/utils/db.py 保持同一路子。

用法:
    python tools/seed_sessions.py                 # 今天起 3 天, 幂等补种
    python tools/seed_sessions.py --days 7        # 今天起 7 天
    python tools/seed_sessions.py --dry-run       # 只打印计划, 不连库
    python tools/seed_sessions.py --reset         # 先删本脚本生成的, 再重排
    python tools/seed_sessions.py --movie 101     # 只补某部影片

只增不改(默认): 已存在的场次一律跳过(INITIAL IGNORE 语义)。
--reset 的删除范围被双重限定: 脚本自己的 ID 区间 + 目标日期窗口,
不会误伤历史订单关联的场次。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

# ---- 排片参数(与 sql/04_extra_demo_data.sql 保持一致) ----

#: 时段序号(1..4) -> 开场时间
SLOT_TIMES = ("10:00:00", "14:30:00", "19:30:00", "22:00:00")

#: 时段序号(1..4) -> 票价
SLOT_PRICES = (
    Decimal("39.90"),   # 早场
    Decimal("49.90"),   # 午场
    Decimal("59.90"),   # 晚场
    Decimal("45.90"),   # 夜场
)

#: 只有 1 号厅(96 座)和 2 号厅(140 座)在 seat 表里有种子座位数据。
#: 另外两个厅是雪花 ID 的测试厅, 不纳入演示排片。
DEMO_HALLS = (1, 2)

#: 参与排片的影片上限: 排除雪花 ID(测试数据), 避开 BIGINT 溢出。
MAX_MOVIE_ID = 1000

#: 本脚本生成的 ID 全部落在这个区间, --reset 与计数逻辑据此限定范围。
#:
#: 上界必须**推导**而不是手写。手写过一次 11299999, 但 movie_id 最大 112,
#: 112 * 1e7 = 1120000000 已远超该值 —— 结果 --reset 删不掉自己种的场次,
#: 每日分布统计也漏掉绝大多数。改为从实际取模规则推导, 以后改参数也不会再错。
#:
#: 注意: 真实影片 ID 上限远小于 MAX_MOVIE_ID(库里是 1..112), 这里用
#: MAX_MOVIE_ID 推导会让区间偏宽, 把 sql/02、sql/04 种的老场次也圈进来
#: (实测 846 条)。这是**有意的**: --reset 的删除条件是
#: 「ID 区间 + 今天起 N 天」双重限定, 日期窗口才是主力防线,
#: ID 区间只负责排除雪花 ID 区。宁可宽不可窄 —— 窄了会漏删自己的数据。
_ID_LOW_MAX = (MAX_MOVIE_ID - 1) * 10_000_000          # 影片段上界(排除 ==1000)
_ID_HALL_MAX = max(DEMO_HALLS) * 100_000               # 影厅段
_ID_DAY_SLOT_MAX = 31 * 100 + len(SLOT_TIMES)          # DAYOFMONTH*100 + slot

ID_MIN = 10_000_000
ID_MAX = _ID_LOW_MAX + _ID_HALL_MAX + _ID_DAY_SLOT_MAX


def demo_id_band_covers(session_id_value: int) -> bool:
    """该 ID 是否落在"本脚本生成的场次"区间内。

    --reset 与计数都靠它划界。雪花 ID(1e18 量级)恒为 False,
    所以永远不会误删真实订单关联的场次。
    """
    return ID_MIN <= session_id_value <= ID_MAX

REPO_ROOT = Path(__file__).resolve().parent.parent
DEV_YML = REPO_ROOT / "cinema-server" / "src" / "main" / "resources" / "application-dev.yml"


# ============================ 纯逻辑(被单元测试覆盖) ============================

def session_id(movie_id: int, hall_id: int, day: date, slot: int) -> int:
    """生成确定性场次 ID —— 绝对日期编码。

    与 sql/05_refresh_sessions_for_demo.sql 的规则一致, 也与 cinema 库里
    2026-10-12 的真实行(movie 1 / hall 2 / slot 1 -> 10201201)对得上。

    段位分配(互不进位):
        movie*1e7 : 影片
        hall *1e5 : 影厅, 最大 2 -> 200000
        dom  *100 : DAYOFMONTH 1..31, 乘 100 后占 4 位
        slot     : 1..4
    最大低位 31*100+4 = 3104 < 100000, 不会串到 hall 段。
    """
    if not 1 <= slot <= len(SLOT_TIMES):
        raise ValueError(f"slot 必须在 1..{len(SLOT_TIMES)}, 收到 {slot}")
    return movie_id * 10_000_000 + hall_id * 100_000 + day.day * 100 + slot


def slot_time(slot: int) -> str:
    """时段序号 -> 开场时间字符串 'HH:MM:SS'。"""
    if not 1 <= slot <= len(SLOT_TIMES):
        raise ValueError(f"slot 必须在 1..{len(SLOT_TIMES)}, 收到 {slot}")
    return SLOT_TIMES[slot - 1]


def slot_price(slot: int) -> Decimal:
    """时段序号 -> 票价。"""
    if not 1 <= slot <= len(SLOT_PRICES):
        raise ValueError(f"slot 必须在 1..{len(SLOT_PRICES)}, 收到 {slot}")
    return SLOT_PRICES[slot - 1]


def hall_for(movie_id: int, day_offset: int, slot: int) -> int:
    """1 号厅 / 2 号厅轮转 —— 与 sql/04 的 MOD(movie+d+s,2) 同规则。

    轮转而非固定, 是为了让相邻时段散落在不同影厅, 演示时选座画面更有变化。
    """
    return DEMO_HALLS[0] if (movie_id + day_offset + slot) % 2 == 0 else DEMO_HALLS[1]


@dataclass(frozen=True)
class SessionPlan:
    """一条待插入的场次(尚未落库)。"""

    id: int
    movie_id: int
    hall_id: int
    start_time: datetime
    price: Decimal

    @property
    def day(self) -> date:
        return self.start_time.date()


def plan_sessions(
    movie_ids: list[int],
    base_date: date,
    days: int = 3,
    slots: int = len(SLOT_TIMES),
) -> list[SessionPlan]:
    """算出要插入的场次清单(纯函数, 不碰数据库)。

    Args:
        movie_ids: 参与排片的影片 ID。
        base_date: 起始日期(通常是今天)。
        days: 排几天(含 base_date 当天), 默认 3。
        slots: 每天几个时段, 默认 4。

    Returns:
        按 (影片, 日期, 时段) 排序的 SessionPlan 列表。
    """
    if days < 1:
        raise ValueError(f"days 至少为 1, 收到 {days}")
    if not 1 <= slots <= len(SLOT_TIMES):
        raise ValueError(f"slots 必须在 1..{len(SLOT_TIMES)}, 收到 {slots}")

    plans: list[SessionPlan] = []
    for movie_id in sorted(movie_ids):
        for offset in range(days):
            day = base_date + timedelta(days=offset)
            for slot in range(1, slots + 1):
                hour, minute, second = (int(x) for x in slot_time(slot).split(":"))
                plans.append(
                    SessionPlan(
                        id=session_id(movie_id, hall_for(movie_id, offset, slot), day, slot),
                        movie_id=movie_id,
                        hall_id=hall_for(movie_id, offset, slot),
                        start_time=datetime(day.year, day.month, day.day, hour, minute, second),
                        price=slot_price(slot),
                    )
                )
    return plans


# ============================ 数据库读写(薄封装) ============================

class Mysql:
    """mysql.exe 子进程封装(不引三方驱动)。"""

    def __init__(self, user: str, password: str, db: str, host: str = "127.0.0.1"):
        self.user, self.password, self.db, self.host = user, password, db, host

    def _run(self, sql: str) -> str:
        args = [
            "mysql", f"-u{self.user}", f"-p{self.password}",
            "-N", "-B", "--default-character-set=utf8mb4",
            self.db, "-h", self.host, "-e", sql,
        ]
        out = subprocess.run(
            args, capture_output=True, shell=False, timeout=120,
            encoding="utf-8", errors="ignore",
        )
        if out.returncode != 0:
            raise RuntimeError(f"mysql 执行失败: {(out.stderr or '').strip()}")
        return (out.stdout or "").strip()

    def rows(self, sql: str) -> list[tuple[str, ...]]:
        text = self._run(sql)
        return [tuple(line.split("\t")) for line in text.splitlines() if line]

    def exec(self, sql: str) -> str:
        return self._run(sql)


def read_dev_config() -> tuple[str, str, str, str]:
    """从 application-dev.yml 抠出 (user, password, db, host)。

    不引 PyYAML —— 那几行结构固定, 正则足够, 也省一个依赖。
    密码是本机开发库口令, 不会被打印到 stdout。
    """
    if not DEV_YML.exists():
        raise FileNotFoundError(
            f"找不到 {DEV_YML}\n"
            f"请先按 AGENTS.md 把 application-dev.yml.example 复制成 application-dev.yml。"
        )
    text = DEV_YML.read_text(encoding="utf-8")

    def grab(pattern: str, default: str) -> str:
        m = re.search(pattern, text)
        return m.group(1) if m else default

    user = grab(r"username:\s*(\S+)", "root")
    password = grab(r"password:\s*(\S+)", "")
    url = grab(r"url:\s*jdbc:mysql://([^:/]+):(\d+)/(\w+)", "127.0.0.1:3306/cinema")
    m = re.match(r"([^:/]+):(\d+)/(\w+)", url)
    host, db = (m.group(1), m.group(3)) if m else ("127.0.0.1", "cinema")
    return user, password, db, host


def active_movie_ids(db: Mysql, only: int | None = None) -> list[int]:
    """取参与排片的影片 ID: status=1 且 id < 1000(排除雪花 ID 测试数据)。

    ⚠️ WHERE 条件必须全部拼在 ORDER BY **之前**。
    实测踩过: 写成 `... ORDER BY id AND id=999` 是非法 SQL, MySQL 不报错,
    而是静默丢掉 AND 条件返回全表 —— 于是 `--movie` 完全失效, 却毫无征兆。
    这个错误必须用一条真库用例挡住, 纯函数测不出来。

    only 指定单片时查不到就返回空列表 —— 调用方据此中止,
    不允许静默回退成全量排片。only 走 int() 强转, 杜绝字符串拼进 SQL。
    """
    where = f"WHERE status=1 AND id<{MAX_MOVIE_ID}"
    if only is not None:
        where += f" AND id={int(only)}"
    sql = f"SELECT id FROM movie {where} ORDER BY id"
    return [int(r[0]) for r in db.rows(sql)]


def movie_durations(db: Mysql, ids: list[int]) -> dict[int, int]:
    """取影片时长 —— end_time = start_time + duration。"""
    if not ids:
        return {}
    rows = db.rows(f"SELECT id, duration FROM movie WHERE id IN ({','.join(map(str, ids))})")
    return {int(r[0]): int(r[1]) for r in rows}


def insert_sessions(db: Mysql, plans: list[SessionPlan], durations: dict[int, int]) -> int:
    """批量 INSERT IGNORE。返回成功执行的语句行数(≠实际插入行数)。

    注意: INSERT IGNORE 遇重复主键会跳过, 所以这个数不能用来判断
    "是否真的插入了数据" —— 要用 before/after 计数对比。
    """
    values: list[str] = []
    for p in plans:
        dur = durations[p.movie_id]
        end = p.start_time + timedelta(minutes=dur)
        values.append(
            f"({p.id},{p.movie_id},{p.hall_id},"
            f"'{p.start_time:%Y-%m-%d %H:%M:%S}','{end:%Y-%m-%d %H:%M:%S}',{p.price},1)"
        )
    sql = (
        "INSERT IGNORE INTO `session` "
        "(id,movie_id,hall_id,start_time,end_time,price,status) VALUES "
        + ",".join(values)
    )
    db.exec(sql)
    return len(values)


def count_in_window(db: Mysql, base_date: date, days: int) -> int:
    """统计窗口内、按 ID 区间筛过的场次数 —— 用于判定"是否真的插入了"。"""
    start = base_date.strftime("%Y-%m-%d 00:00:00")
    end = (base_date + timedelta(days=days)).strftime("%Y-%m-%d 00:00:00")
    rows = db.rows(
        f"SELECT COUNT(*) FROM `session` "
        f"WHERE id BETWEEN {ID_MIN} AND {ID_MAX} "
        f"AND start_time>='{start}' AND start_time<'{end}'"
    )
    return int(rows[0][0]) if rows else 0


def delete_generated(db: Mysql, base_date: date, days: int) -> int:
    """删除本脚本生成的场次(双重限定: ID 区间 + 日期窗口)。

    只删本脚本自己的产物, 不碰 02/04 脚本种的历史数据, 也不碰
    ID 落在雪花区间的真实订单关联场次。
    返回删除行数。
    """
    start = base_date.strftime("%Y-%m-%d 00:00:00")
    end = (base_date + timedelta(days=days)).strftime("%Y-%m-%d 00:00:00")
    where = (
        f"id BETWEEN {ID_MIN} AND {ID_MAX} "
        f"AND start_time>='{start}' AND start_time<'{end}'"
    )
    before = count_in_window(db, base_date, days)
    db.exec(f"DELETE FROM `session` WHERE {where}")
    return before


# ============================ CLI ============================

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="补种演示场次(今天起 N 天), 绝对日期 ID, 幂等",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--days", type=int, default=3, help="排几天(含今天), 默认 3")
    ap.add_argument("--slots", type=int, default=len(SLOT_TIMES),
                    help=f"每天几个时段(1..{len(SLOT_TIMES)}), 默认 {len(SLOT_TIMES)}")
    ap.add_argument("--movie", type=int, default=None, help="只补指定影片 ID")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划, 不连库")
    ap.add_argument("--reset", action="store_true",
                    help="先删除本脚本在窗口内生成的场次, 再重排")
    args = ap.parse_args(argv)

    today = date.today()

    # --dry-run 不连库, 用硬编码的库中真实影片 ID 演示计划形状
    if args.dry_run:
        known_ids = [1, 2, 3, 101, 102, 103, 104, 105,
                     106, 107, 108, 109, 110, 111, 112]
        if args.movie is not None and args.movie not in known_ids:
            print(
                f"[x] 影片 {args.movie} 不在库的影片 ID 范围内 "
                f"({known_ids[0]}..{known_ids[-1]}, 不含雪花 ID 测试片)。已中止。",
                file=sys.stderr,
            )
            return 1
        ids = [args.movie] if args.movie is not None else known_ids
        plans = plan_sessions(ids, today, args.days, args.slots)
        print(f"[dry-run] 今天={today}  排 {args.days} 天 × {args.slots} 时段 "
              f"× {len(ids)} 部影片 = {len(plans)} 场")
        print(f"[dry-run] ID 区间: {ID_MIN}..{ID_MAX}")
        for p in plans[:5]:
            print(f"  id={p.id} movie={p.movie_id} hall={p.hall_id} "
                  f"{p.start_time:%m-%d %H:%M} ¥{p.price}")
        if len(plans) > 5:
            print(f"  ... 其余 {len(plans) - 5} 场")
        return 0

    try:
        user, password, dbname, host = read_dev_config()
    except FileNotFoundError as e:
        print(f"[x] {e}", file=sys.stderr)
        return 2

    db = Mysql(user, password, dbname, host)
    try:
        movies = active_movie_ids(db, args.movie)
    except RuntimeError as e:
        print(f"[x] 连库失败: {e}", file=sys.stderr)
        return 2

    if not movies:
        if args.movie is not None:
            print(
                f"[x] 影片 {args.movie} 不存在, 或 status != 1(雪花 ID 测试片已下线)。\n"
                f"    已中止 —— 不会退回成给所有影片排片。",
                file=sys.stderr,
            )
        else:
            print(
                "[x] 没有 status=1 且 id<1000 的影片可排。\n"
                "    先灌 sql/01_schema.sql + sql/02_init_data.sql(+ 可选 sql/04_extra_demo_data.sql)。",
                file=sys.stderr,
            )
        return 1

    if args.reset:
        deleted = delete_generated(db, today, args.days)
        print(f"[1/3] --reset: 已删除窗口内本脚本生成的 {deleted} 场")

    plans = plan_sessions(movies, today, args.days, args.slots)
    before = count_in_window(db, today, args.days)

    durations = movie_durations(db, movies)
    missing = [m for m in movies if m not in durations]
    if missing:
        print(f"[x] 这些影片查不到 duration: {missing}")
        return 1

    insert_sessions(db, plans, durations)
    after = count_in_window(db, today, args.days)

    print(f"[2/3] 计划 {len(plans)} 场 "
          f"({len(movies)} 部影片 × {args.days} 天 × {args.slots} 时段)")
    print(f"[3/3] 窗口内场次: {before} -> {after} (净增 {after - before})")

    if after == before and not args.reset:
        print("      · 幂等命中: 这些场次之前就存在, 本次全部跳过(不是失败)")
    elif after < len(plans) and not args.reset:
        print(f"      ! 计划 {len(plans)} 场但只有 {after} 场在窗口内, 请核对")

    # 逐日概览, 让人一眼看出三个 tab 都有货
    print("      每日分布:")
    for i in range(args.days):
        d = today + timedelta(days=i)
        rows = db.rows(
            f"SELECT COUNT(*) FROM `session` WHERE DATE(start_time)='{d:%Y-%m-%d}' "
            f"AND id BETWEEN {ID_MIN} AND {ID_MAX}"
        )
        cnt = int(rows[0][0]) if rows else 0
        label = {0: "今天", 1: "明天", 2: "后天"}.get(i, f"+{i}天")
        print(f"        {d:%Y-%m-%d} ({label:>3}): {cnt} 场")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
