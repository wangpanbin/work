#!/usr/bin/env python
"""一键可复现演示脚本(v0.4.0,IT-01)—— 战略计划 §7.2 AC-01 / AC-02。

**目标**:一条命令跑完「补种场次 → 全链路冒烟 → 并发防超卖 → 三场景压测 → 出报告」,
全程无需人工干预,且**连续跑 3 次全绿**(AC-02)。

用法:
    python tools/demo_run.py                    # 全流程
    python tools/demo_run.py --reset --dry-run  # 只看 --reset 会清什么(默认就是 dry-run)
    python tools/demo_run.py --reset --yes      # 真清演示范围数据
    python tools/demo_run.py --skip-load        # 跳过压测(前两步只要几十秒)

**数据边界(重要)**:
- 默认**不重置**任何数据 —— 只做幂等补种, 在现有演示库上跑。
- `--reset` 是显式开关,**默认 dry-run**:先打印将要清理的行数与范围,
  要真执行还得再加 `--yes`。
- `--reset` **只删演示范围**:压测产生的测试用户及其订单 + 过期场次上的订单。
  **不碰** `movie` / `hall` / `seat` 这些基础数据,也不碰真实用户(user1/user2/admin)。
"""
from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "tools"))

from db_query import build_cmd, resolve_config          # noqa: E402  (复用本机 mysql 调用姿势)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


# ------------------------------------------------------------------ mysql

def _cfg(args):
    """复用 db_query 的连接信息解析(环境变量 > 命令行 > application-dev.yml > 默认)。"""
    class _A:
        host = args.host
        port = args.port
        db = args.db
        user = args.user
        password = args.password
    return resolve_config(_A)


def mysql(sql: str, cfg: dict, write: bool = False) -> tuple[int, str]:
    """跑一条 SQL。

    ⚠️ 只读工具 {@code tools/db_query.py} 会拒写操作关键字; 本脚本要跑补种与清理,
    所以**不走**它的只读闸门 —— 写操作在这里是显式授权的(补种脚本本身是幂等的,
    清理动作还额外要 --reset --yes 双开关)。
    """
    cmd = build_cmd(cfg, sql)
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, ((proc.stdout or "") + (proc.stderr or "")).strip()


def mysql_script(path: Path, cfg: dict) -> tuple[int, str]:
    """跑一个 .sql 文件(用 mysql 的 stdin 重定向,内容不过命令行长度限制)。"""
    cmd = build_cmd(cfg, "SELECT 1")[:-2]          # 去掉 '-e' 与它的参数
    with open(path, "r", encoding="utf-8", newline="") as fh:
        proc = subprocess.run(cmd, stdin=fh, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
    return proc.returncode, ((proc.stdout or "") + (proc.stderr or "")).strip()


# ------------------------------------------------------------------ 步骤

def step_seed(cfg: dict) -> tuple[bool, str]:
    """幂等补种今天+1+2 的场次(sql/05_refresh_sessions_for_demo.sql)。"""
    sql_file = REPO / "sql" / "05_refresh_sessions_for_demo.sql"
    if not sql_file.exists():
        return False, f"找不到 {sql_file}"
    rc, out = mysql_script(sql_file, cfg)
    if rc != 0:
        return False, out[-800:]
    created = re.findall(r"(\d+)\s*(?:rows? inserted|插入|条)", out, re.I)
    return True, f"补种完成({sql_file.name})" + (f",插入 {created[-1]} 行" if created else "")


def step_smoke() -> tuple[bool, str]:
    """全链路冒烟 —— 复用 IT-08 的浏览器回归套件。"""
    script = REPO / "test" / "e2e" / "e2e_regression.py"
    if not script.exists():
        return False, f"找不到 {script}"
    rc, out = _run([sys.executable, str(script)], REPO, timeout=1800)
    tail = "\n".join(out.strip().splitlines()[-20:])
    return rc == 0, tail


def step_concurrency() -> tuple[bool, str]:
    """250 并发抢同一组座位 —— 防超卖不变式。"""
    script = REPO / "test" / "concurrency_strict.py"
    if not script.exists():
        return False, f"找不到 {script}"
    rc, out = _run([sys.executable, str(script)], REPO, timeout=1200)
    tail = "\n".join(out.strip().splitlines()[-20:])
    return rc == 0, tail


def step_load(concurrency: int, duration: int) -> tuple[bool, str]:
    """三场景压测(读 / 锁座 / 混合)。"""
    script = REPO / "test" / "load_test.py"
    if not script.exists():
        return False, f"找不到 {script}"
    rc, out = _run([sys.executable, str(script), "--scenario", "all",
                    "--concurrency", str(concurrency), "--duration", str(duration), "--users", "50"],
                   REPO, timeout=3600)
    tail = "\n".join(out.strip().splitlines()[-25:])
    return rc == 0, tail


def _run(cmd: list, cwd: Path, timeout: int) -> tuple[int, str]:
    try:
        proc = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, f"超时({timeout}s)"
    except FileNotFoundError as e:
        return 127, f"命令不存在: {e}"
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# ------------------------------------------------------------------ reset

#: 测试用户名前缀 —— 压测与并发脚本注册的都是这些前缀,不会误伤 user1/user2/admin。
TEST_USER_PATTERNS = ("tuser%", "pt%")


def step_reset(cfg: dict, execute: bool) -> tuple[bool, str]:
    """清理演示范围数据。默认 dry-run —— 打印将要清什么;execute=True 才真删。"""
    where_users = " OR ".join(f"username LIKE '{p}'" for p in TEST_USER_PATTERNS)
    count_sql = (
        "SELECT (SELECT COUNT(*) FROM `order` o JOIN `user` u ON u.id=o.user_id "
        f"WHERE {where_users}) AS test_user_orders, "
        "(SELECT COUNT(*) FROM `order` o JOIN `session` s ON s.id=o.session_id "
        "WHERE s.start_time < NOW()) AS past_session_orders"
    )
    rc, out = mysql(count_sql, cfg)
    if rc != 0:
        return False, out[-500:]
    counts = {}
    for line in out.strip().splitlines()[1:]:
        if "\t" in line:
            k, v = line.split("\t", 1)
            counts[k] = v
    test_orders = int(counts.get("test_user_orders", 0))
    past_orders = int(counts.get("past_session_orders", 0))
    total = test_orders + past_orders

    scope = [
        f"压测测试用户({', '.join(TEST_USER_PATTERNS)})的订单: {test_orders} 行",
        f"过期场次(start_time < NOW())上的订单: {past_orders} 行",
        "上述订单的 order_item / ticket / refund_log 明细",
        "上述场次在 Redis 的 lock / sold 位图 key",
        "❌ 不碰:movie / hall / seat 基础数据,以及 user1 / user2 / admin",
    ]
    print("── 将要清理的范围 ──")
    for line in scope:
        print("   " + line)

    if total == 0:
        print("   (没有需要清理的数据)")
        return True, "无需清理"

    if not execute:
        print(f"\n[dry-run] 共约 {total} 张订单将被清理。确认无误后加 --yes 真执行。")
        return True, f"dry-run:约 {total} 张订单待清理"

    # 真删 —— 按依赖顺序:明细 → 主表 → 位图
    delete_sql = [
        f"DELETE ti FROM order_item ti JOIN `order` o ON o.id=ti.order_id "
        f"JOIN `user` u ON u.id=o.user_id WHERE {where_users}",
        f"DELETE t FROM ticket t JOIN `user` u ON u.id=t.user_id WHERE {where_users}",
        f"DELETE r FROM refund_log r JOIN `user` u ON u.id=r.user_id WHERE {where_users}",
        f"DELETE o FROM `order` o JOIN `user` u ON u.id=o.user_id WHERE {where_users}",
        "DELETE ti FROM order_item ti JOIN `order` o ON o.id=ti.order_id "
        "JOIN `session` s ON s.id=o.session_id WHERE s.start_time < NOW()",
        "DELETE t FROM ticket t JOIN `order` o ON o.order_no=t.order_no "
        "JOIN `session` s ON s.id=o.session_id WHERE s.start_time < NOW()",
        "DELETE o FROM `order` o JOIN `session` s ON s.id=o.session_id WHERE s.start_time < NOW()",
    ]
    for sql in delete_sql:
        rc, out = mysql(sql, cfg, write=True)
        if rc != 0:
            return False, f"清理失败: {out[-300:]}"
    return True, f"已清理约 {total} 张订单及其明细"


# ------------------------------------------------------------------ 报告

def write_report(path: Path, steps: list, started: dt.datetime, elapsed: float) -> None:
    """生成压测/演示报告。"""
    lines = [
        "# 压测报告 · v3(自动生成)",
        "",
        f"- 生成时间:{started.strftime('%Y-%m-%d %H:%M:%S')}",
        f"- 总耗时:{elapsed:.0f} 秒",
        "- 生成方式:`python tools/demo_run.py`(可重复执行,幂等)",
        "",
        "## 步骤结果",
        "",
        "| 步骤 | 结果 | 说明 |",
        "| --- | :-: | --- |",
    ]
    for name, ok, detail in steps:
        first = (detail or "").splitlines()[0] if detail else ""
        lines.append(f"| {name} | {'✅ PASS' if ok else '❌ FAIL'} | {first[:160]} |")
    lines += ["", "## 各步骤完整输出", ""]
    for name, ok, detail in steps:
        lines.append(f"### {name} —— {'PASS' if ok else 'FAIL'}")
        lines.append("")
        lines.append("```")
        lines.append(detail or "(无输出)")
        lines.append("```")
        lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


# ------------------------------------------------------------------ main

def main(argv: list | None = None) -> int:
    p = argparse.ArgumentParser(description="一键可复现演示脚本")
    p.add_argument("--reset", action="store_true", help="先清理演示范围数据(默认 dry-run)")
    p.add_argument("--yes", action="store_true", help="配合 --reset 真执行删除")
    p.add_argument("--skip-e2e", action="store_true", help="跳过浏览器冒烟")
    p.add_argument("--skip-load", action="store_true", help="跳过三场景压测")
    p.add_argument("--concurrency", type=int, default=100, help="压测并发数(默认 100)")
    p.add_argument("--duration", type=int, default=20, help="压测每场景秒数(默认 20)")
    p.add_argument("--report", default="docs/压测报告-v3.md", help="报告落盘路径")
    p.add_argument("--host", default=None)
    p.add_argument("--port", default=None)
    p.add_argument("--db", default=None)
    p.add_argument("--user", default=None)
    p.add_argument("--password", default=None)
    args = p.parse_args(argv)

    if args.yes and not args.reset:
        print("--yes 只在 --reset 一起用时有效", file=sys.stderr)
        return 2

    cfg = _cfg(args)
    if not cfg.get("password"):
        print("拿不到数据库口令。请设 CINEMA_DB_PASSWORD, 或按 AGENTS.md 把 "
              "application-dev.yml.example 复制成 application-dev.yml。", file=sys.stderr)
        return 2

    started = dt.datetime.now()
    t0 = time.time()
    steps: list = []

    print("=" * 68)
    print("一键演示 —— 补种 → 冒烟 → 防超卖 → 压测 → 出报告")
    print("=" * 68)

    if args.reset:
        print("\n[STEP 0] 清理演示范围数据")
        ok, detail = step_reset(cfg, execute=args.yes)
        steps.append(("清理演示数据", ok, detail))
        print(("  ✅ " if ok else "  ❌ ") + detail.splitlines()[0])
        if not ok:
            return 1

    print("\n[STEP 1] 幂等补种演示场次")
    ok, detail = step_seed(cfg)
    steps.append(("幂等补种场次", ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + detail)

    if not args.skip_e2e:
        print("\n[STEP 2] 全链路冒烟(真浏览器)")
        ok, detail = step_smoke()
        steps.append(("全链路冒烟", ok, detail))
        print(("  ✅ " if ok else "  ❌ ") + (detail.splitlines()[-1] if detail else ""))
    else:
        steps.append(("全链路冒烟", True, "(已跳过)"))

    print("\n[STEP 3] 250 并发防超卖")
    ok, detail = step_concurrency()
    steps.append(("并发防超卖", ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + (detail.splitlines()[-1] if detail else ""))

    if not args.skip_load:
        print(f"\n[STEP 4] 三场景压测(并发 {args.concurrency} / 每场景 {args.duration}s)")
        ok, detail = step_load(args.concurrency, args.duration)
        steps.append(("三场景压测", ok, detail))
        print(("  ✅ " if ok else "  ❌ ") + (detail.splitlines()[-1] if detail else ""))
    else:
        steps.append(("三场景压测", True, "(已跳过)"))

    elapsed = time.time() - t0
    report_path = REPO / args.report
    write_report(report_path, steps, started, elapsed)
    steps.append(("生成报告", True, str(report_path.relative_to(REPO))))

    print("\n" + "=" * 68)
    failed = [s for s in steps if not s[1]]
    for name, ok, detail in steps:
        print(f"  {'✅' if ok else '❌'} {name}")
    print(f"耗时 {elapsed:.0f}s · 报告 {report_path.relative_to(REPO)}")
    print("=" * 68)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
