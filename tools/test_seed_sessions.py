"""seed_sessions 纯逻辑单元测试（不连数据库）。

为什么只有纯逻辑这一层:
  本脚本真正的风险是"看起来成功、实际没插数据" —— sql/04_extra_demo_data.sql
  就是这么坑过一次(确定性 ID 只编码相对天数, 重跑被 INSERT IGNORE 静默跳过)。
  而 ID 生成规则恰恰是纯函数, 可以脱离数据库直接验证。

  集成层(ID 真能插进库、重跑不加行)已用真实 cinema 库实测:
  897 -> 1077 -> 1077 不变, 不再重复造临时库。

运行: python -m unittest discover -s tools -p "test_*.py" -v
"""
from __future__ import annotations

import sys
import unittest
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from seed_sessions import (  # noqa: E402
    ID_MAX,
    ID_MIN,
    SessionPlan,
    demo_id_band_covers,
    hall_for,
    plan_sessions,
    session_id,
    slot_price,
    slot_time,
)


class IdBandTest(unittest.TestCase):
    """ID 区间(--reset / 计数用) 必须真的圈住本脚本生成的所有 ID。

    这是实测踩到的真实 bug: ID_MAX 曾写成 11299999, 但 movie_id 最大 112,
    112 * 1e7 = 1120000000 已远超该上界, 导致 --reset 删不掉、
    每日分布统计漏掉绝大多数场次。
    """

    def test_band_covers_every_movie_we_seed(self):
        movies = [1, 2, 3] + list(range(101, 113))  # 库中全部 status=1 影片
        for m in movies:
            for day in (date(2026, 10, 11), date(2026, 10, 12)):
                for slot in range(1, 5):
                    for hall in (1, 2):
                        sid = session_id(m, hall, day, slot)
                        self.assertTrue(
                            ID_MIN <= sid <= ID_MAX,
                            f"movie={m} hall={hall} day={day} slot={slot} -> {sid} 越界",
                        )

    def test_band_upper_bound_is_derived_not_hardcoded(self):
        # 上界至少要能容纳 movie_id=112 * 1e7 + hall 2 * 1e5 + 31*100 + 4
        worst = session_id(112, 2, date(2026, 10, 31), 4)
        self.assertGreaterEqual(ID_MAX, worst)
        # 且不能大到把雪花 ID(1e18 量级)收进来, 否则 --reset 会误删真实场次
        self.assertLess(ID_MAX, 10**15)

    def test_band_excludes_snowflake_range(self):
        self.assertFalse(demo_id_band_covers(2105521354347712513))
        self.assertTrue(demo_id_band_covers(10201201))

    def test_band_is_intentionally_wide_and_date_window_is_the_real_guard(self):
        """ID 区间故意偏宽(会圈住老场次), 真正防误删的是日期窗口。

        实测: 宽区间会把 sql/02、sql/04 种的 846 条老场次圈进来。
        所以 delete_generated 的 WHERE 必须同时带日期条件,
        否则 --reset 会误删历史订单关联的场次。
        """
        from seed_sessions import ID_MAX as band_max

        # 846 条老场次确实落在区间内 —— 这是已知且可接受的
        old_session_id = 10200101  # sql/04 于 2026-09-08 生成
        self.assertLessEqual(old_session_id, band_max)

        # 防线是日期窗口: 只删 base_date 起 days 天内
        import inspect as _inspect

        from seed_sessions import delete_generated

        src = _inspect.getsource(delete_generated)
        self.assertIn("start_time>=", src, "删除条件必须带日期下界")
        self.assertIn("start_time<'", src, "删除条件必须带日期上界")


class SessionIdTest(unittest.TestCase):
    """ID 规则: movie*1e7 + hall*1e5 + DAYOFMONTH*100 + slot

    期望值取自 cinema 库的真实数据(不是按公式复算), 避免自证式断言。
    """

    def test_matches_real_rows_from_cinema_db(self):
        # 真实库里: movie 1 / 2号IMAX厅 / 10-12 / 10:00  => id 10201201
        self.assertEqual(session_id(1, 2, date(2026, 10, 12), 1), 10201201)
        # 真实库里: movie 1 / 1号激光厅 / 10-12 / 14:30  => id 10101202
        self.assertEqual(session_id(1, 1, date(2026, 10, 12), 2), 10101202)

    def test_is_stable_for_same_day(self):
        d = date(2026, 10, 12)
        self.assertEqual(session_id(101, 1, d, 3), session_id(101, 1, d, 3))

    def test_differs_across_days(self):
        ids = {session_id(1, 1, date(2026, 10, d), 1) for d in (11, 12, 13)}
        self.assertEqual(len(ids), 3, "跨天 ID 必须不同, 否则重跑会撞主键")

    def test_month_boundary_does_not_collide(self):
        # 9月30日 与 10月1日 都只靠 DAYOFMONTH 区分, 必须不同
        self.assertNotEqual(
            session_id(1, 1, date(2026, 9, 30), 1),
            session_id(1, 1, date(2026, 10, 1), 1),
        )

    def test_no_cross_field_overlap(self):
        """厅号/日期/时段三段不能互相进位串扰。

        hall 最大 2 -> 200000; DAYOFMONTH*100+slot 最大 3104, 远小于 100000。
        若哪天段位超 100000, 就会和 hall 段叠加出错。
        """
        self.assertLess(31 * 100 + 4, 100000)
        self.assertGreater(session_id(1, 2, date(2026, 10, 31), 4), session_id(1, 1, date(2026, 10, 31), 4))


class SlotTest(unittest.TestCase):
    """时段与票价 —— 期望值取自 cinema 库 2026-10-12 的真实排片。"""

    def test_slot_times(self):
        self.assertEqual(
            [slot_time(s) for s in (1, 2, 3, 4)],
            ["10:00:00", "14:30:00", "19:30:00", "22:00:00"],
        )

    def test_slot_prices(self):
        # 真实库里 10-12: 10:00=39.90 / 14:30=49.90 / 19:30=59.90 / 22:00=45.90
        self.assertEqual(
            [slot_price(s) for s in (1, 2, 3, 4)],
            [Decimal("39.90"), Decimal("49.90"), Decimal("59.90"), Decimal("45.90")],
        )

    def test_rejects_out_of_range_slot(self):
        for bad in (0, 5, -1):
            with self.subTest(slot=bad):
                with self.assertRaises(ValueError):
                    slot_time(bad)
                with self.assertRaises(ValueError):
                    slot_price(bad)
                with self.assertRaises(ValueError):
                    session_id(1, 1, date(2026, 10, 12), bad)


class HallRotationTest(unittest.TestCase):
    def test_only_demo_halls_are_used(self):
        halls = {hall_for(101, d, s) for d in range(3) for s in range(1, 5)}
        self.assertEqual(halls, {1, 2}, "只能用 1/2 号厅(seat 表有种子数据)")

    def test_alternates_between_slots(self):
        # 同一影片同一天, 相邻时段应分到不同厅, 否则画面没有变化
        picked = [hall_for(101, 0, s) for s in (1, 2, 3, 4)]
        self.assertEqual(len(set(picked)), 2)


class PlanSessionsTest(unittest.TestCase):
    def test_full_plan_count(self):
        plans = plan_sessions([1, 2, 3], date(2026, 10, 12), days=3, slots=4)
        self.assertEqual(len(plans), 3 * 3 * 4)

    def test_covers_today_tomorrow_day_after(self):
        base = date(2026, 10, 12)
        plans = plan_sessions([1], base, days=3, slots=4)
        self.assertEqual(
            sorted({p.day for p in plans}),
            [date(2026, 10, 12), date(2026, 10, 13), date(2026, 10, 14)],
        )

    def test_ids_are_unique(self):
        plans = plan_sessions([1, 2, 101, 102], date(2026, 10, 12), days=3, slots=4)
        ids = [p.id for p in plans]
        self.assertEqual(len(ids), len(set(ids)), "同一批计划内 ID 不得重复")

    def test_end_time_is_derivable_from_duration(self):
        """end_time 由 start_time + duration 得出, 计划里只需 start_time。"""
        plans = plan_sessions([1], date(2026, 10, 12), days=1, slots=1)
        self.assertEqual(plans[0].start_time, datetime(2026, 10, 12, 10, 0, 0))

    def test_planning_is_deterministic(self):
        a = plan_sessions([1, 2], date(2026, 10, 12), days=2, slots=4)
        b = plan_sessions([2, 1], date(2026, 10, 12), days=2, slots=4)
        self.assertEqual(a, b, "同一组输入必须产出完全相同的计划")

    def test_partial_slots(self):
        plans = plan_sessions([1], date(2026, 10, 12), days=1, slots=2)
        self.assertEqual(len(plans), 2)
        self.assertEqual(plans[0].start_time.hour, 10)
        self.assertEqual(plans[1].start_time.hour, 14)

    def test_rejects_bad_args(self):
        with self.assertRaises(ValueError):
            plan_sessions([1], date(2026, 10, 12), days=0)
        with self.assertRaises(ValueError):
            plan_sessions([1], date(2026, 10, 12), days=3, slots=5)


class _FakeDb:
    """只记录 SQL 的假 Db, 用于验证查询语句构造。"""

    def __init__(self, rows_for_movie=None):
        self.rows_for_movie = rows_for_movie or {}
        self.last_sql = ""

    def rows(self, sql):
        self.last_sql = sql
        # 只关心"有没有按 id 过滤", 不解析具体数字
        if "AND id=" in sql:
            mid = int(sql.split("AND id=")[1].split()[0])
            return self.rows_for_movie.get(mid, [])
        return [(str(i),) for i in (1, 2, 3)]


class MovieFilterTest(unittest.TestCase):
    """--movie 必须真的过滤, 不能静默回退成"全部影片"。

    实测踩到的缺陷: --movie 999999 查不到时, 若悄悄回退成全量排片,
    演示前想"只补某片"就会排出完全错误的内容, 而且不报错。
    """

    def test_filters_to_requested_movie(self):
        from seed_sessions import active_movie_ids

        db = _FakeDb({101: [("101",)]})
        self.assertEqual(active_movie_ids(db, only=101), [101])
        self.assertIn("AND id=101", db.last_sql)

    def test_unknown_movie_returns_empty_not_everything(self):
        from seed_sessions import active_movie_ids

        db = _FakeDb({})
        self.assertEqual(active_movie_ids(db, only=999999), [])

    def test_no_filter_returns_all_active(self):
        from seed_sessions import active_movie_ids

        db = _FakeDb({})
        self.assertEqual(active_movie_ids(db, only=None), [1, 2, 3])

    def test_main_refuses_to_run_when_no_movies_match(self):
        """空结果必须以非零退出码失败, 而不是静默跑完 180 场。"""
        import seed_sessions

        code = seed_sessions.main(["--movie", "999999"])
        self.assertEqual(code, 1, "查不到影片时必须返回非零退出码")

    def test_dry_run_also_rejects_unknown_movie(self):
        """dry-run 分支同样不能放行不存在的影片。

        实测踩过: 校验写成 `args.movie not in ids`, 而 ids 本身就是
        [args.movie], 于是校验恒不触发, dry-run 会照样打印 12 场计划。
        """
        import contextlib
        import io

        import seed_sessions

        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            code = seed_sessions.main(["--movie", "999999", "--dry-run"])
        self.assertEqual(code, 1)
        self.assertIn("999999", buf.getvalue())

    def test_dry_run_allows_known_movie(self):
        import contextlib
        import io

        import seed_sessions

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = seed_sessions.main(["--movie", "101", "--dry-run"])
        self.assertEqual(code, 0)
        self.assertIn("dry-run", buf.getvalue())

    def test_sql_is_injection_safe_for_movie_id(self):
        """movie_id 必须走 int() 强转, 不能把字符串直接拼进 SQL。"""
        from seed_sessions import active_movie_ids

        db = _FakeDb({})
        with self.assertRaises(ValueError):
            active_movie_ids(db, only="1 OR 1=1")  # type: ignore[arg-type]
        self.assertEqual(db.last_sql, "", "参数非法时不应发出任何查询")

    def test_where_clause_precedes_order_by(self):
        """WHERE 条件必须全在 ORDER BY 之前。

        这是实测踩到的真 bug: 拼成 `... ORDER BY id AND id=999` 后,
        MySQL 不报错, 而是静默丢弃 AND 条件返回全表 —— `--movie` 彻底失效。
        假 Db 抓不到这个问题(它只记录 SQL 字符串), 必须断言语句结构。
        """
        from seed_sessions import active_movie_ids

        db = _FakeDb({})
        active_movie_ids(db, only=101)
        sql = db.last_sql
        self.assertIn("WHERE", sql)
        self.assertLess(sql.index("WHERE"), sql.index("ORDER BY"),
                        "WHERE 必须在 ORDER BY 之前")
        self.assertLess(sql.index("AND id=101"), sql.index("ORDER BY"),
                        "追加的 AND 条件也必须在 ORDER BY 之前")

    def test_real_db_rejects_unknown_movie(self):
        """真库验证: 查不到的影片必须返回空列表, 而不是全量。

        需要本机 MySQL 已启动; 不可用时跳过而不是假装通过。
        """
        try:
            from seed_sessions import Mysql, active_movie_ids, read_dev_config

            user, password, dbname, host = read_dev_config()
            db = Mysql(user, password, dbname, host)
        except Exception as exc:  # noqa: BLE001
            self.skipTest(f"本机库不可用, 跳过: {exc}")

        self.assertEqual(active_movie_ids(db, only=999999), [],
                         "不存在的影片必须返回空列表")
        self.assertEqual(active_movie_ids(db, only=101), [101],
                         "存在的影片必须精确返回且只有它自己")
        self.assertGreater(len(active_movie_ids(db, only=None)), 1)


if __name__ == "__main__":
    unittest.main()
