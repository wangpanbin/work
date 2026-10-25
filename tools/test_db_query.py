"""db_query 的只读守卫测试 —— 不连数据库,纯逻辑。

**为什么要有这个**:这个工具存在的意义就是「别再手敲 mysql 被 PowerShell 拆坏」。
如果只读守卫失效(比如为了图方便放开了写操作),它就变成一个能误删数据的脚手架。
所以守卫本身必须有测试。
"""
from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import db_query  # noqa: E402


class ReadonlyGuardTest(unittest.TestCase):

    def test_select_allowed(self):
        db_query.assert_readonly("SELECT id FROM hall")
        db_query.assert_readonly("  select 1  ")
        db_query.assert_readonly("SHOW TABLES")
        db_query.assert_readonly("DESCRIBE `order`")
        db_query.assert_readonly("EXPLAIN SELECT 1")

    def test_write_statements_rejected(self):
        for sql in (
            "DELETE FROM `order`",
            "UPDATE `order` SET status=1",
            "INSERT INTO hall VALUES (1)",
            "DROP TABLE hall",
            "ALTER TABLE hall ADD COLUMN x INT",
            "TRUNCATE TABLE hall",
            "CREATE TABLE t (a INT)",
            "GRANT ALL ON *.* TO x",
        ):
            with self.assertRaises(SystemExit, msg=sql):
                db_query.assert_readonly(sql)

    def test_write_keyword_inside_select_rejected(self):
        """SELECT 里裹着写操作关键字 —— 也要挡(比如 `SELECT ... INTO OUTFILE`)。"""
        for sql in (
            "SELECT * FROM hall INTO OUTFILE '/tmp/x'",
            "SELECT 1; DROP TABLE hall",
            "SELECT * FROM hall FOR UPDATE",
        ):
            with self.assertRaises(SystemExit, msg=sql):
                db_query.assert_readonly(sql)

    def test_empty_statement_rejected(self):
        with self.assertRaises(SystemExit):
            db_query.assert_readonly("   ")


class CommandBuilderTest(unittest.TestCase):
    """命令行的拼法是本工具的核心价值 —— 参数与值之间必须留空格。"""

    def test_args_are_space_separated(self):
        cmd = db_query.build_cmd(
            {"host": "127.0.0.1", "port": "3306", "user": "root", "password": "pw", "db": "cinema"},
            "SELECT 1",
        )
        self.assertIn("-h", cmd)
        self.assertIn("127.0.0.1", cmd)
        # 紧凑写法(-h127.0.0.1)会被 PowerShell 拆坏,必须拆成两项
        self.assertNotIn("-h127.0.0.1", cmd)
        self.assertIn("-B", cmd)   # batch: tab 分隔输出
        self.assertIn("-e", cmd)
        self.assertEqual("SELECT 1", cmd[-1])

    def test_env_overrides(self):
        os.environ["CINEMA_DB_HOST"] = "db.example"
        os.environ["CINEMA_DB_NAME"] = "other"
        try:
            class A:
                host = None
                port = None
                db = None
                user = None
                password = None
            cfg = db_query.resolve_config(A())
            self.assertEqual("db.example", cfg["host"])
            self.assertEqual("other", cfg["db"])
        finally:
            del os.environ["CINEMA_DB_HOST"]
            del os.environ["CINEMA_DB_NAME"]

    def test_cli_arg_beats_dev_yml(self):
        class A:
            host = "cli-host"
            port = None
            db = None
            user = None
            password = None
        cfg = db_query.resolve_config(A())
        self.assertEqual("cli-host", cfg["host"])

    def test_no_plaintext_password_in_source(self):
        """口令绝不能硬编码进入库文件 —— 必须来自 env 或 gitignore 的 application-dev.yml。"""
        src = (Path(db_query.__file__)).read_text(encoding="utf-8")
        # 只允许出现「从 dev yml 读」的形态,不允许出现形如 password = "xxx" 的字面量
        self.assertNotRegex(src, r"""password\s*=\s*["'][^"']+["']""")
        self.assertNotIn("123456", src)


if __name__ == "__main__":
    unittest.main()
