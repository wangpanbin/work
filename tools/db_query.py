#!/usr/bin/env python
"""只读 SQL 查询 CLI —— 固化「本机怎么正确调 mysql」,别再每次现推。

**为什么要有这个**:直接手敲 `mysql -h127.0.0.1 -uroot -p<pwd> ...` 会被
PowerShell 把 `-h<value>` 这种紧凑写法拆坏,mysql 判定为 usage 错误后**打印整页帮助**
(几千行噪声, 还会把真正的错误埋掉)。正确姿势是**参数与值之间留空格**:
`-h 127.0.0.1 -P 3306 -u root -p<pwd> -D <db> -B -e "<sql>"`。
`tools/reconcile_seat_bitmap.py` 的 `_mysql_cmd()` 里早就是这个写法, 这里把它固化成 CLI。

用法:
    python tools/db_query.py "SELECT id, name, seat_count FROM hall ORDER BY id"
    python tools/db_query.py --db cinema "SELECT COUNT(*) FROM `order`"
    python tools/db_query.py --db cinema --table hall      # 打印建表语句

**连接信息从哪来**(优先级从高到低):
    ① 环境变量 CINEMA_DB_HOST / PORT / NAME / USER / PASSWORD
    ② 命令行 --host/--port/--db/--user/--password
    ③ `cinema-server/src/main/resources/application-dev.yml`(该文件被 gitignore)
    ④ 内置默认值(只含 host/port/db/user —— **口令永不落进任何入库文件**)

⚠️ **只读**:本工具只执行 SELECT / SHOW / DESCRIBE / EXPLAIN。
带 INSERT/UPDATE/DELETE/ALTER/DROP/INTO OUTFILE 的语句会被拒绝 —— 写操作请走
`tools/reconcile_seat_bitmap.py --fix` 这类**显式带 --fix 开关**的工具。
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEV_YML = REPO / "cinema-server" / "src" / "main" / "resources" / "application-dev.yml"

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = "3306"
DEFAULT_DB = "cinema"
DEFAULT_USER = "root"

#: 只读白名单: 语句的第一个关键字必须是其中之一
READONLY_PREFIXES = ("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH")

# 禁写关键字 —— 即使前面套了 SELECT 也不放行
# INTO / OUTFILE / DUMPFILE 能把查询结果写文件,同样是写操作
FORBIDDEN = re.compile(
    r"\b(INSERT|UPDATE|DELETE|REPLACE|ALTER|DROP|TRUNCATE|CREATE|GRANT|SET|INTO|OUTFILE|DUMPFILE)\b",
    re.IGNORECASE,
)


def read_dev_config() -> dict:
    """从 application-dev.yml 读连接信息 —— 该文件被 gitignore, 口令不落进任何入库文件。

    与 `tools/reconcile_seat_bitmap.py#read_db_config` 同一套读法(不引 PyYAML,正则够了)。
    """
    raw = ""
    if DEV_YML.exists():
        raw = DEV_YML.read_text(encoding="utf-8", newline="")

    def pick(key: str) -> str:
        m = re.search(r"(?m)^\s*%s\s*:\s*(.+)$" % key, raw)
        return m.group(1).strip() if m else ""

    url = pick("url")
    db = DEFAULT_DB
    m = re.search(r"//[^/]+/([^/?]+)", url)
    if m:
        db = m.group(1)
    return {
        "host": DEFAULT_HOST,
        "port": DEFAULT_PORT,
        "user": pick("username") or DEFAULT_USER,
        "password": pick("password"),
        "db": db,
    }


def build_cmd(cfg: dict, sql: str) -> list:
    """拼 mysql 命令行 —— 参数与值之间必须留空格(紧凑写法会被 PowerShell 拆坏)。"""
    return [
        "mysql",
        "-h", cfg["host"],
        "-P", str(cfg["port"]),
        "-u", cfg["user"],
        "-p" + cfg["password"],
        "-D", cfg["db"],
        "-B",           # batch: tab 分隔, 无表格边框
        "-e", sql,
    ]


def resolve_config(args) -> dict:
    """环境变量 > 命令行 > application-dev.yml > 内置默认值。"""
    dev = read_dev_config()

    def pick(env_key: str, cli, dev_val: str, default: str) -> str:
        v = os.environ.get(env_key)
        if v:
            return v
        if cli:
            return cli
        if dev_val:
            return dev_val
        return default

    return {
        "host": pick("CINEMA_DB_HOST", args.host, dev["host"], DEFAULT_HOST),
        "port": pick("CINEMA_DB_PORT", args.port, dev["port"], DEFAULT_PORT),
        "user": pick("CINEMA_DB_USER", args.user, dev["user"], DEFAULT_USER),
        "password": pick("CINEMA_DB_PASSWORD", args.password, dev["password"], ""),
        "db": pick("CINEMA_DB_NAME", args.db, dev["db"], DEFAULT_DB),
    }


def assert_readonly(sql: str) -> None:
    stripped = sql.strip()
    if not stripped:
        raise SystemExit("SQL 为空")
    head = stripped.split(None, 1)[0].upper()
    if head not in READONLY_PREFIXES:
        raise SystemExit(
            f"只读工具: 语句必须以 {'/'.join(READONLY_PREFIXES)} 开头(实际是 {head})。"
            "写操作请走带 --fix 开关的专用工具。"
        )
    hit = FORBIDDEN.search(stripped)
    if hit:
        raise SystemExit(f"只读工具: 语句里出现写操作关键字 {hit.group(0).upper()}, 已拒绝")


def main(argv: list | None = None) -> int:
    p = argparse.ArgumentParser(description="只读 SQL 查询(本机 cinema 库)")
    p.add_argument("sql", nargs="?", help="要执行的只读 SQL")
    p.add_argument("--db", default=None, help="库名(默认取 application-dev.yml, 一般是 cinema)")
    p.add_argument("--host", default=None)
    p.add_argument("--port", default=None)
    p.add_argument("--user", default=None)
    p.add_argument("--password", default=None, help="不给就从 application-dev.yml / 环境变量读")
    p.add_argument("--table", help="打印指定表的建表语句(SHOW CREATE TABLE)")
    args = p.parse_args(argv)

    if args.table:
        sql = f"SHOW CREATE TABLE `{args.table}`"
    elif args.sql:
        sql = args.sql
    else:
        p.print_help()
        return 2

    try:
        assert_readonly(sql)
    except SystemExit as e:
        print(str(e), file=sys.stderr)
        return 1

    cfg = resolve_config(args)
    if not cfg["password"]:
        print("拿不到数据库口令。请设 CINEMA_DB_PASSWORD, 或按 AGENTS.md 把 "
              "application-dev.yml.example 复制成 application-dev.yml。", file=sys.stderr)
        return 1

    proc = subprocess.run(build_cmd(cfg, sql), capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print("mysql 失败: " + (proc.stderr or "").strip(), file=sys.stderr)
        return proc.returncode
    out = (proc.stdout or "").strip()
    if out:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
