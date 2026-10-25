"""reconcile_seat_bitmap 的单元测试 + 集成测试。

分三层:
  ① 纯逻辑(分组/幽灵判定/处理策略) —— 不连任何东西, 永远可跑;
  ② Redis 集成(位图截断脚本) —— 需要本机 Redis, 没有就跳过;
  ③ MySQL 集成(幽灵订单清理 SQL) —— 需要本机 MySQL, 没有就跳过;
     集成测试**建临时库**跑, 不碰开发库。

为什么必须有 ③: 这套工具的失败模式是「看起来成功、实际没清掉」——
sql/04_extra_demo_data.sql 就是这么坑过一次。而「清掉了没有」只能对着
真数据验。

运行:
    python -m unittest discover -s tools -p "test_*.py" -v
"""
from __future__ import annotations

import io
import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import reconcile_seat_bitmap as R  # noqa: E402


# ============================== ① 纯逻辑 ==============================

class GroupOrderRowsTest(unittest.TestCase):
    """扁平明细 → 订单分组。"""

    def row(self, oid, order_no, status, seat_index, exists, session="100", hall="2",
            hall_seat_count=140, seat_count=1, price="45.90"):
        return (oid, order_no, session, str(status), str(seat_count), price,
                hall, str(hall_seat_count), str(seat_index), price, "1" if exists else "0")

    def test_groups_rows_by_order(self):
        orders = R.group_order_rows([
            self.row("1", "A", 0, 5, True),
            self.row("1", "A", 0, 6, True),
            self.row("2", "B", 1, 7, True),
        ])
        self.assertEqual(set(orders), {"1", "2"})
        self.assertEqual(len(orders["1"]["seats"]), 2)
        self.assertEqual([s["seat_index"] for s in orders["1"]["seats"]], [5, 6])
        self.assertTrue(orders["1"]["seats"][0]["exists"])

    def test_keeps_metadata(self):
        orders = R.group_order_rows([self.row("1", "A", 2, 5, True)])
        o = orders["1"]
        self.assertEqual(o["order_no"], "A")
        self.assertEqual(o["status"], 2)
        self.assertEqual(o["hall_seat_count"], 140)
        self.assertEqual(o["total_amount"], "45.90")


class PickGhostOrdersTest(unittest.TestCase):
    """幽灵订单 = 含该影厅里不存在的座位。"""

    def test_detects_order_with_out_of_range_seat(self):
        orders = R.group_order_rows([
            ("1", "A", "100", "0", "1", "45.90", "2", "140", "5", "45.90", "1"),
            ("1", "A", "100", "0", "1", "45.90", "2", "140", "100000", "45.90", "0"),
        ])
        ghosts = R.pick_ghost_orders(orders)
        self.assertEqual(len(ghosts), 1)
        self.assertEqual(ghosts[0]["invalid_seats"], [100000])
        self.assertEqual([s["seat_index"] for s in ghosts[0]["valid_seats"]], [5])

    def test_clean_order_is_not_a_ghost(self):
        orders = R.group_order_rows([
            ("1", "A", "100", "0", "1", "45.90", "2", "140", "139", "45.90", "1"),
        ])
        self.assertEqual(R.pick_ghost_orders(orders), [])

    def test_multiple_ghosts_are_all_returned(self):
        orders = R.group_order_rows([
            ("1", "A", "100", "0", "1", "1", "2", "140", "140", "1", "0"),
            ("2", "B", "100", "1", "1", "1", "2", "140", "1000000", "1", "0"),
        ])
        self.assertEqual(len(R.pick_ghost_orders(orders)), 2)


class PlanGhostActionTest(unittest.TestCase):
    """处理策略分档 —— 决定脚本会不会碰「钱」。"""

    def test_paid_order_is_report_only(self):
        self.assertEqual(R.plan_ghost_action(R.STATUS_PAID, 0), R.ACTION_REPORT_ONLY)
        self.assertEqual(R.plan_ghost_action(R.STATUS_PAID, 3), R.ACTION_REPORT_ONLY)

    def test_refunding_and_refunded_keep_the_amount(self):
        self.assertEqual(R.plan_ghost_action(R.STATUS_REFUNDING, 0), R.ACTION_PURGE_KEEP_AMOUNT)
        self.assertEqual(R.plan_ghost_action(R.STATUS_REFUNDED, 1), R.ACTION_PURGE_KEEP_AMOUNT)

    def test_pending_without_valid_seats_gets_cancelled(self):
        self.assertEqual(R.plan_ghost_action(R.STATUS_PENDING, 0), R.ACTION_PURGE_AND_CANCEL)

    def test_pending_with_valid_seats_is_purged(self):
        self.assertEqual(R.plan_ghost_action(R.STATUS_PENDING, 2), R.ACTION_PURGE)

    def test_cancelled_order_with_valid_seats_is_purged(self):
        self.assertEqual(R.plan_ghost_action(R.STATUS_CANCELLED, 2), R.ACTION_PURGE)

    def test_paid_never_reaches_a_write_action(self):
        # 回归锁: 已支付的幽灵订单**绝不能**被自动改成取消/退款
        for valid in range(0, 5):
            action = R.plan_ghost_action(R.STATUS_PAID, valid)
            self.assertNotIn(action, (R.ACTION_PURGE, R.ACTION_PURGE_AND_CANCEL,
                                      R.ACTION_PURGE_KEEP_AMOUNT))


# ============================== ② Redis 集成 ==============================

def _redis_available() -> bool:
    try:
        p = subprocess.run(["redis-cli", "-h", R.REDIS_HOST, "-p", R.REDIS_PORT, "PING"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        return p.returncode == 0 and "PONG" in p.stdout
    except Exception:  # noqa: BLE001
        return False


REDIS_UP = _redis_available()


@unittest.skipUnless(REDIS_UP, "本机没有可用的 Redis")
class TrimBitmapLuaTest(unittest.TestCase):
    """位图截断脚本 —— 用独立临时键, 不碰任何业务键。"""

    KEY = "tmp:test:reconcile:trim"

    def setUp(self):
        R.redis_cli("DEL", self.KEY)

    def tearDown(self):
        R.redis_cli("DEL", self.KEY)

    def _setbits(self, offsets):
        for off in offsets:
            R.redis_setbit(self.KEY, off, 1)

    def test_keeps_legal_bits_and_drops_out_of_range(self):
        self._setbits([3, 139, 141, 100000])
        res = R.trim_bitmap(self.KEY, 140)
        self.assertEqual(res["out_bits"], 2)          # 141 与 100000
        self.assertTrue(res["truncated"])
        self.assertEqual(res["bytes_before"], 12501)  # 幽灵位把位图撑到 12KB
        self.assertEqual(res["bytes_after"], 18)      # 140 位 = 18 字节
        bits = R.redis_key_bits(self.KEY)
        self.assertIn(3, bits)
        self.assertIn(139, bits)
        self.assertNotIn(141, bits)
        self.assertNotIn(100000, bits)

    def test_physically_shrinks_the_key(self):
        """回归锁: 光清零高位**不会**让 Redis 收缩位图字符串。

        实测 offset=100000 的位清零后 STRLEN 仍是 12501 —— 所以必须 DEL+重建。
        """
        self._setbits([3, 100000])
        R.redis_setbit(self.KEY, 100000, 0)          # 只清零, 不重建
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY)), 12501)
        R.trim_bitmap(self.KEY, 140)
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY)), 1)

    def test_only_ghost_bits_leaves_empty_bitmap(self):
        self._setbits([100000])
        res = R.trim_bitmap(self.KEY, 140)
        self.assertEqual(res["bytes_after"], 0)
        self.assertEqual(R.redis_key_bits(self.KEY), set())

    def test_clean_bitmap_is_left_alone(self):
        self._setbits([3, 139])
        before = int(R.redis_cli("STRLEN", self.KEY))
        res = R.trim_bitmap(self.KEY, 140)
        self.assertEqual(res["out_bits"], 0)
        self.assertFalse(res["truncated"])
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY)), before)
        self.assertEqual(sorted(R.redis_key_bits(self.KEY)), [3, 139])

    def test_tail_bits_inside_last_byte_are_cleared(self):
        # seat_count=140 → 最后一个字节覆盖 136..143, 其中 140..143 是越界位
        self._setbits([3, 141])
        res = R.trim_bitmap(self.KEY, 140)
        self.assertEqual(res["out_bits"], 1)
        bits = R.redis_key_bits(self.KEY)
        self.assertEqual(sorted(bits), [3])


# ============================== ③ MySQL 集成 ==============================

def _mysql_available() -> bool:
    try:
        cfg = R.read_db_config()
        p = subprocess.run(R._mysql_cmd(cfg, "SELECT 1;"), capture_output=True,
                           text=True, encoding="utf-8", errors="replace")
        return p.returncode == 0
    except Exception:  # noqa: BLE001
        return False


MYSQL_UP = _mysql_available()
TEST_DB = os.environ.get("CINEMA_GHOST_TEST_DB", "cinema_ghost_it")


@unittest.skipUnless(MYSQL_UP, "本机没有可用的 MySQL")
class TempDbCase(unittest.TestCase):
    """临时库基类: 每个用例一套全新库, 绝不碰开发库。

    建库/删库不能带 -D(库还不存在时 mysql 会直接报 Unknown database),
    库内的语句必须带 -D。
    """

    DB = os.environ.get("CINEMA_GHOST_TEST_DB", "cinema_ghost_it")

    def setUp(self):
        self.cfg = dict(R.read_db_config())
        self.cfg["db"] = self.DB
        self._sql_no_db("DROP DATABASE IF EXISTS `%s`;" % self.DB)
        self._sql_no_db("CREATE DATABASE `%s`;" % self.DB)
        self._load_schema()
        self._setup_fixture()

    def tearDown(self):
        self._sql_no_db("DROP DATABASE IF EXISTS `%s`;" % self.DB)

    def _sql_no_db(self, sql):
        cmd = ["mysql", "-h", self.cfg["host"], "-P", str(self.cfg["port"]),
               "-u", self.cfg["user"], "-p" + self.cfg["password"], "-B", "-e", sql]
        p = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if p.returncode != 0:
            raise AssertionError("SQL 失败: %s\n%s" % (sql[:120], (p.stderr or "")[:400]))
        return p.stdout

    def _sql(self, sql):
        p = subprocess.run(R._mysql_cmd(self.cfg, sql), capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if p.returncode != 0:
            raise AssertionError("SQL 失败: %s\n%s" % (sql[:120], (p.stderr or "")[:400]))
        return p.stdout

    def _load_schema(self):
        schema = R.REPO_ROOT / "sql" / "01_schema.sql"
        if not schema.exists():
            raise unittest.SkipTest("找不到 sql/01_schema.sql")
        # ⚠️ 01_schema.sql 里有 `CREATE DATABASE cinema; USE cinema;` ——
        # 直接灌进去会把表建到**开发库**里去, 临时库仍然是空的。
        # 这里把这两行去掉, 由 -D <临时库> 决定建到哪。
        with io.open(schema, encoding="utf-8") as f:
            text = f.read()
        lines = [l for l in text.splitlines()
                 if not l.strip().upper().startswith("CREATE DATABASE")
                 and not l.strip().upper().startswith("USE ")]
        p = subprocess.run(
            ["mysql", "-h", self.cfg["host"], "-P", str(self.cfg["port"]),
             "-u", self.cfg["user"], "-p" + self.cfg["password"], "-D", self.cfg["db"]],
            input="\n".join(lines), capture_output=True, text=True,
            encoding="utf-8", errors="replace")
        if p.returncode != 0:
            raise unittest.SkipTest("装载 schema 失败: " + (p.stderr or "")[:200])

    def _setup_fixture(self):
        self._sql("INSERT INTO cinema (id, name) VALUES (1, '测试影院');")
        self._sql("INSERT INTO hall (id, cinema_id, name, seat_rows, seat_cols, seat_count) "
                  "VALUES (1, 1, '测试厅', 10, 14, 140);")
        # 一次插完 140 行(逐行插要起 140 个 mysql 进程, 慢到没法用)
        values = ", ".join("(%d, 1, %d, %d, %d)" % (1000 + i, i, i // 14 + 1, i % 14 + 1)
                           for i in range(140))
        self._sql("INSERT INTO seat (id, hall_id, seat_index, row_no, col_no) VALUES " + values + ";")
        self._sql("INSERT INTO movie (id, title, duration) VALUES (1, '测试影片', 100);")
        self._sql("INSERT INTO `session` (id, movie_id, hall_id, start_time, end_time, price, status) "
                  "VALUES (100, 1, 1, '2030-01-01 19:00:00', '2030-01-01 21:00:00', 45.90, 1);")

    def _last_row(self, sql):
        """取查询结果的最后一行(去掉表头)。"""
        out = R.mysql(sql, self.cfg)
        lines = [l for l in out.splitlines() if l.strip()]
        return lines[-1] if len(lines) > 1 else ""

    def _seed(self, order_id, order_no, status, session_id, seat_index, seat_count=1,
              price="45.90"):
        R.mysql_exec(
            "INSERT INTO `order` (id, order_no, user_id, session_id, status, total_amount, "
            "seat_count, expire_at) VALUES (%d, '%s', 1, %d, %d, %s, %d, '2030-01-01 00:00:00');"
            % (order_id, order_no, session_id, status, price, seat_count), self.cfg)
        R.mysql_exec(
            "INSERT INTO order_item (id, order_id, session_id, seat_index, price) "
            "VALUES (%d, %d, %d, %d, %s);"
            % (order_id * 10, order_id, session_id, seat_index, price), self.cfg)

    def _fetch_rows(self):
        return R.mysql(
            "SELECT o.id, o.order_no, o.session_id, o.status, o.seat_count, o.total_amount, "
            "       s.hall_id, h.seat_count, oi.seat_index, oi.price, "
            "       IF(st.id IS NULL, 0, 1) "
            "FROM `order` o "
            "JOIN order_item oi ON oi.order_id = o.id "
            "JOIN `session` s ON s.id = o.session_id "
            "JOIN hall h ON h.id = s.hall_id "
            "LEFT JOIN seat st ON st.hall_id = s.hall_id AND st.seat_index = oi.seat_index "
            "ORDER BY o.session_id, o.order_no, oi.seat_index;", self.cfg)

    def _ghosts(self):
        rows = [tuple(l.split("\t")) for l in self._fetch_rows().splitlines()[1:]]
        return R.pick_ghost_orders(R.group_order_rows(rows))


class GhostPurgeDbTest(TempDbCase):
    """对着**临时库**跑一遍真正的清理 SQL。"""

    def test_purge_removes_out_of_range_item_and_cancels_order(self):
        self._seed(9001, "GHOST-1", R.STATUS_PENDING, 100, 100000)
        ghosts = self._ghosts()
        self.assertEqual(len(ghosts), 1)
        self.assertEqual(ghosts[0]["invalid_seats"], [100000])
        self.assertEqual(R.plan_ghost_action(ghosts[0]["status"], 0), R.ACTION_PURGE_AND_CANCEL)

        # 真正执行清理(与 purge_ghost_orders 里的 SQL 同款)
        R.mysql_exec("DELETE FROM order_item WHERE order_id = 9001 AND seat_index IN (100000);",
                     self.cfg)
        R.mysql_exec("UPDATE `order` SET status = %d, seat_count = 0, total_amount = 0 "
                     "WHERE id = 9001 AND status = %d;" % (R.STATUS_CANCELLED, R.STATUS_PENDING),
                     self.cfg)

        rows = self._fetch_rows()
        self.assertNotIn("100000", rows)
        self.assertEqual(
            self._last_row("SELECT status, seat_count, total_amount FROM `order` WHERE id = 9001;"),
            "2\t0\t0.00")

    def test_paid_ghost_order_keeps_its_rows(self):
        """回归锁: 已支付的幽灵订单**不能**被脚本自动改动。"""
        self._seed(9002, "GHOST-PAID", R.STATUS_PAID, 100, 100000)
        rows = R.mysql("SELECT seat_index FROM order_item WHERE order_id = 9002;", self.cfg)
        self.assertIn("100000", rows)
        paid = [g for g in self._ghosts() if g["order_no"] == "GHOST-PAID"]
        self.assertEqual(len(paid), 1)
        self.assertEqual(R.plan_ghost_action(paid[0]["status"], 0), R.ACTION_REPORT_ONLY)

    def test_recompute_seat_count_after_purge(self):
        # 一张单里两个座位: 一个合法, 一个越界
        self._seed(9003, "MIX-1", R.STATUS_PENDING, 100, 5, seat_count=2)
        R.mysql_exec("INSERT INTO order_item (id, order_id, session_id, seat_index, price) "
                     "VALUES (90031, 9003, 100, 100000, 45.90);", self.cfg)
        R.mysql_exec("DELETE FROM order_item WHERE order_id = 9003 AND seat_index IN (100000);",
                     self.cfg)
        R.mysql_exec(
            "UPDATE `order` SET "
            "  seat_count = (SELECT COUNT(*) FROM order_item oi WHERE oi.order_id = `order`.id), "
            "  total_amount = (SELECT COALESCE(SUM(oi.price), 0) FROM order_item oi "
            "                   WHERE oi.order_id = `order`.id) "
            "WHERE id = 9003;", self.cfg)
        out = self._last_row("SELECT seat_count, total_amount FROM `order` WHERE id = 9003;")
        self.assertEqual(out, "1\t45.90")

    def test_collect_db_state_ignores_out_of_range_seats(self):
        """回归锁: 越界座位不能算进「应该被占」。

        否则清掉位图之后对账会把这些座本报成「缺失」——
        把一次成功的清理说成一次危险的漂移。
        """
        self._seed(9004, "GHOST-2", R.STATUS_PAID, 100, 100000)
        cfg = dict(self.cfg)
        saved = R.read_db_config
        R.read_db_config = lambda: cfg
        try:
            state = R.collect_db_state()
        finally:
            R.read_db_config = saved
        occupied = state.get("100", {}).get("occupied", set())
        self.assertNotIn(100000, occupied)


@unittest.skipUnless(MYSQL_UP and REDIS_UP, "需要本机 MySQL + Redis")
class GhostModeCliTest(TempDbCase):
    """端到端: 真的把脚本当子进程跑一遍。

    走完整链路 —— 子进程 + 环境变量指向临时库与临时键空间 ——
    验证的是「命令行的默认行为是不是安全的(默认只报告)」与
    「--fix 真的把东西清掉了」这两件事, 不是内部函数的自洽。
    """

    PREFIX = "tmp:test:reconcile:cli:"
    SESSION = 100
    KEY_LOCK = PREFIX + "session:100:lock"
    KEY_SOLD = PREFIX + "session:100:sold"

    def setUp(self):
        super().setUp()
        # 一张待支付的幽灵订单(座位 100000 越界)
        self._seed(9101, "CLI-GHOST", R.STATUS_PENDING, self.SESSION, 100000)
        # 位图: 一个合法位 + 一个越界位
        R.redis_setbit(self.KEY_LOCK, 5, 1)
        R.redis_setbit(self.KEY_LOCK, 100000, 1)
        R.redis_setbit(self.KEY_SOLD, 5, 1)

    def tearDown(self):
        R.redis_cli("DEL", self.KEY_LOCK)
        R.redis_cli("DEL", self.KEY_SOLD)
        super().tearDown()

    def _run(self, *args):
        env = dict(os.environ)
        env["CINEMA_DB_NAME"] = self.DB
        env["CINEMA_REDIS_PREFIX"] = self.PREFIX
        env["PYTHONIOENCODING"] = "utf-8"
        p = subprocess.run(
            [sys.executable, str(R.REPO_ROOT / "tools" / "reconcile_seat_bitmap.py"), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
        return p

    def test_default_run_only_reports(self):
        """默认只报告 —— 不带 --fix 一律不写。"""
        p = self._run("--purge-ghost-orders")
        self.assertIn("幽灵订单", p.stdout)
        self.assertIn("100000", p.stdout)
        # 数据没被动过
        out = R.mysql("SELECT seat_index FROM order_item WHERE order_id = 9101;", self.cfg)
        self.assertIn("100000", out)
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY_LOCK)), 12501)

    def test_fix_cleans_db_and_bitmap(self):
        p = self._run("--purge-ghost-orders", "--fix")
        self.assertIn("已处理", p.stdout)
        # DB: 越界明细没了, 订单被置为已取消
        out = R.mysql("SELECT seat_index FROM order_item WHERE order_id = 9101;", self.cfg)
        self.assertNotIn("100000", out)
        self.assertEqual(
            self._last_row("SELECT status, seat_count FROM `order` WHERE id = 9101;"), "2\t0")
        # Redis: 越界位清掉, 合法位保留, 体积降回来
        bits = R.redis_key_bits(self.KEY_LOCK)
        self.assertEqual(sorted(bits), [5])
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY_LOCK)), 1)

    def test_fix_dry_run_writes_nothing(self):
        p = self._run("--purge-ghost-orders", "--fix", "--dry-run")
        self.assertIn("dry-run", p.stdout)
        out = R.mysql("SELECT seat_index FROM order_item WHERE order_id = 9101;", self.cfg)
        self.assertIn("100000", out)
        self.assertEqual(int(R.redis_cli("STRLEN", self.KEY_LOCK)), 12501)


if __name__ == "__main__":
    unittest.main()
