"""check_redis_safety 的单元测试(不连 Redis)。

锁的是「解析与判定」这两层 —— 它们决定了这份清单会不会**误报成安全**。
探针最坏的失效模式不是报错, 而是「明明有问题却报 PASS」。

运行:
    python -m unittest discover -s tools -p "test_*.py" -v
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_redis_safety as C  # noqa: E402


class ParseBytesTest(unittest.TestCase):
    """Redis 的内存数值有纯数字也有带单位的。"""

    def test_plain_number(self):
        self.assertEqual(C.parse_bytes("1048576"), 1048576)

    def test_empty_is_zero(self):
        self.assertEqual(C.parse_bytes(""), 0)
        self.assertEqual(C.parse_bytes("   "), 0)

    def test_with_unit(self):
        self.assertEqual(C.parse_bytes("512mb"), 512 * 1024 ** 2)
        self.assertEqual(C.parse_bytes("1gb"), 1024 ** 3)
        self.assertEqual(C.parse_bytes("64kb"), 64 * 1024)

    def test_with_unit_and_space(self):
        self.assertEqual(C.parse_bytes("512 MB"), 512 * 1024 ** 2)

    def test_garbage_is_zero(self):
        # 解析不出来必须当 0(= 未设置)而不是抛异常 ——
        # 探针本身崩了就等于没检查。
        self.assertEqual(C.parse_bytes("abc"), 0)


class HumanBytesTest(unittest.TestCase):
    def test_bytes(self):
        self.assertEqual(C.human_bytes(512), "512B")

    def test_kb(self):
        self.assertEqual(C.human_bytes(2048), "2.0KB")

    def test_mb(self):
        self.assertEqual(C.human_bytes(512 * 1024 ** 2), "512.0MB")


class ReportTest(unittest.TestCase):
    """报告结构的分级与退出码。"""

    def test_all_pass_exits_zero(self):
        rep = C.Report()
        rep.ok("a", "detail")
        self.assertEqual(rep.print_all(), 0)

    def test_warn_alone_exits_zero(self):
        """警告不该阻断 —— 只是「知会一下」。"""
        rep = C.Report()
        rep.warn("a", "detail")
        self.assertEqual(rep.print_all(), 0)

    def test_fail_exits_one(self):
        rep = C.Report()
        rep.fail("a", "detail")
        self.assertEqual(rep.print_all(), 1)

    def test_fail_with_warn_still_exits_one(self):
        rep = C.Report()
        rep.warn("a", "detail")
        rep.fail("b", "detail")
        self.assertEqual(rep.print_all(), 1)


class PolicyJudgmentTest(unittest.TestCase):
    """回归锁: 驱逐策略必须是 noeviction。

    这条是整份检查里最要紧的一项 —— 别的策略(allsrvolatile / allkeys / …)
    都会在内存触顶时**驱逐锁定位图**, 那批座位立刻回到可选池 = 超卖。
    """

    def test_noeviction_is_ok(self):
        self.assertEqual(C.Report().print_all(), 0)  # 基线

    def test_other_policies_must_fail(self):
        for policy in ("allkeys-lru", "allkeys-lfu", "volatile-lru", "allkeys-random"):
            rep = C.Report()
            rep.fail("maxmemory-policy = %s" % policy, "会驱逐键")
            self.assertEqual(rep.print_all(), 1, policy)


if __name__ == "__main__":
    unittest.main()
