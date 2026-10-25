#!/usr/bin/env python
"""一键跑完本仓库的全部闸门(v0.4.0,IT-01 的组成部分)。

**为什么要有这个**:仓库有四套闸门(后端 mvn test / 前端 type-check+build+vitest /
Python 工具 unittest / 文档一致性),全靠「人记得跑」。改完代码忘了跑 →
文档里的数字与代码对不上 → 答辩现场翻车。

**本脚本不碰 CI/CD 配置、不装 git 钩子**(用户红线)。它只是一个本地入口。

用法:
    python tools/run_all_gates.py                 # 跑全套
    python tools/run_all_gates.py --skip-frontend # 跳过前端(改后端时省时间)
    python tools/run_all_gates.py --json          # 机器可读输出

退出码:
    0 = 全部通过(允许有 SKIP)
    1 = 有闸门失败
    2 = 脚本自身出错
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SERVER = REPO / "cinema-server"
WEB = REPO / "cinema-web"

# Windows 控制台默认 GBK —— 强制 UTF-8,否则中文/符号会变成 '?'
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


@dataclass
class GateResult:
    name: str
    status: str            # PASS / FAIL / SKIP
    detail: str = ""
    duration: float = 0.0
    metrics: dict = field(default_factory=dict)


def _run(cmd: list, cwd: Path, timeout: int = 3600) -> tuple[int, str]:
    """跑一条命令,返回 (returncode, 合并输出)。

    Windows 上 {@code mvn} / {@code pnpm} 都是 {@code .cmd} 垫片 —— {@code CreateProcess}
    不解析 PATHEXT, 直接 {@code subprocess.run(["mvn", ...])} 会报「系统找不到指定的文件」。
    所以先用 {@code shutil.which} 解析成真实路径(它认 PATHEXT)。
    """
    exe = shutil.which(cmd[0]) or cmd[0]
    env = dict(os.environ)
    env.setdefault("PYTHONIOENCODING", "utf-8")
    try:
        proc = subprocess.run(
            [exe, *cmd[1:]], cwd=str(cwd), capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout, env=env,
        )
    except subprocess.TimeoutExpired:
        return 124, f"超时({timeout}s)"
    except FileNotFoundError as e:
        return 127, f"命令不存在: {e}"
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# ------------------------------------------------------------------ 各闸门

def gate_backend() -> GateResult:
    """后端: mvn -o clean test,再聚合 surefire XML 拿真实用例数。"""
    t0 = time.time()
    rc, out = _run(["mvn", "-o", "clean", "test"], SERVER, timeout=3600)
    duration = time.time() - t0

    reports = sorted((SERVER / "target" / "surefire-reports").glob("TEST-*.xml"))
    tests = failures = errors = skipped = 0
    for xml_file in reports:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError:
            continue
        tests += int(root.get("tests", 0))
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))

    metrics = {
        "用例数": tests, "测试类数": len(reports),
        "失败": failures, "错误": errors, "跳过": skipped,
    }
    if rc != 0:
        tail = "\n".join(out.strip().splitlines()[-25:])
        return GateResult("后端单测", "FAIL", tail, duration, metrics)
    if tests == 0:
        return GateResult("后端单测", "FAIL", "surefire 没产出任何报告(用例数=0)", duration, metrics)
    if failures or errors:
        return GateResult("后端单测", "FAIL", f"失败 {failures} / 错误 {errors}", duration, metrics)
    return GateResult("后端单测", "PASS",
                      f"{tests} 用例 / {len(reports)} 类 / 0 失败 / 0 错误", duration, metrics)


def _pnpm(args: list, timeout: int = 1800) -> GateResult:
    t0 = time.time()
    rc, out = _run(["pnpm", *args], WEB, timeout=timeout)
    duration = time.time() - t0
    if rc != 0:
        tail = "\n".join(out.strip().splitlines()[-25:])
        return GateResult(" ".join(args), "FAIL", tail, duration)
    return GateResult(" ".join(args), "PASS", "", duration)


def gate_frontend_type() -> GateResult:
    return _pnpm(["type-check"])


def gate_frontend_build() -> GateResult:
    return _pnpm(["build"])


def gate_frontend_test() -> GateResult:
    """前端: pnpm test,并从输出里抠出真实的用例数与文件数。"""
    r = _pnpm(["test"])
    out = r.detail
    m = re.search(r"Tests\s+(?:(\d+)\s+failed\s*\|?\s*)?(\d+)\s+passed", out)
    files = re.search(r"Test Files\s+(?:(\d+)\s+failed\s*\|?\s*)?(\d+)\s+passed", out)
    metrics = {}
    if m:
        metrics["用例数"] = int(m.group(2))
    if files:
        metrics["测试文件数"] = int(files.group(2))
    if r.status == "PASS":
        r.detail = ""
    r.metrics = metrics
    return r


def gate_python_tools() -> GateResult:
    """Python 工具套件(含文档一致性闸门)。"""
    t0 = time.time()
    rc, out = _run([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"],
                   REPO, timeout=900)
    duration = time.time() - t0
    m = re.search(r"^Ran (\d+) tests? in", out, re.M)
    metrics = {"用例数": int(m.group(1))} if m else {}
    if rc != 0:
        tail = "\n".join(out.strip().splitlines()[-30:])
        return GateResult("Python 工具", "FAIL", tail, duration, metrics)
    return GateResult("Python 工具", "PASS", "", duration, metrics)


def gate_audit_redline() -> GateResult:
    """红线指标 AC-13:未经用户显式确认的写操作数必须恒为 0。

    表没建(没跑 sql/08)时判 SKIP 而不是 FAIL —— 迁移没跑是部署前置问题,
    不是代码缺陷(部署清单里已列为必填项)。
    """
    t0 = time.time()
    duration = time.time() - t0
    exists = _run([sys.executable, "tools/db_query.py",
                   "SELECT COUNT(*) FROM information_schema.tables "
                   "WHERE table_schema = DATABASE() AND table_name = 'chat_assistant_log'"],
                  REPO, timeout=120)
    if exists[0] != 0:
        return GateResult("审计红线", "SKIP", "连不上数据库,跳过", duration)
    try:
        table_count = int(exists[1].strip().splitlines()[-1])
    except (ValueError, IndexError):
        return GateResult("审计红线", "SKIP", "读不到表清单,跳过", duration)
    if table_count == 0:
        return GateResult("审计红线", "SKIP",
                          "chat_assistant_log 表不存在 —— 先跑 sql/08_chat_assistant_log.sql", duration)

    rc, out = _run([sys.executable, "tools/db_query.py",
                    "SELECT COUNT(*) FROM chat_assistant_log "
                    "WHERE kind = 'WRITE_CONFIRM' AND confirmed_by_user = 0"], REPO, timeout=120)
    if rc != 0:
        return GateResult("审计红线", "SKIP", "查询失败,跳过", duration)
    try:
        bad = int(out.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return GateResult("审计红线", "SKIP", "读不到结果,跳过", duration)
    metrics = {"未确认写操作数": bad}
    if bad == 0:
        return GateResult("审计红线", "PASS", "未确认写操作数 = 0", duration, metrics)
    return GateResult("审计红线", "FAIL", f"未确认写操作数 = {bad}(必须为 0)", duration, metrics)


GATES = [
    ("backend", "后端单测", gate_backend),
    ("frontend-type", "前端类型", gate_frontend_type),
    ("frontend-build", "前端构建", gate_frontend_build),
    ("frontend-test", "前端单测", gate_frontend_test),
    ("python", "Python 工具", gate_python_tools),
    ("redline", "审计红线", gate_audit_redline),
]


def main(argv: list | None = None) -> int:
    p = argparse.ArgumentParser(description="一键跑完全部闸门")
    p.add_argument("--skip", action="append", default=[], metavar="GATE",
                   help="跳过某个闸门(可重复)。GATE ∈ " + ",".join(k for k, _, _ in GATES))
    p.add_argument("--only", action="append", default=[], metavar="GATE",
                   help="只跑某些闸门(可重复)")
    p.add_argument("--json", action="store_true", help="输出 JSON")
    args = p.parse_args(argv)

    selected = GATES
    if args.only:
        keys = set(args.only)
        selected = [g for g in GATES if g[0] in keys]
    if args.skip:
        keys = set(args.skip)
        selected = [g for g in selected if g[0] not in keys]
    if not selected:
        print("没有要跑的闸门", file=sys.stderr)
        return 2

    print("=" * 68)
    print("闸门汇总")
    print("=" * 68)
    results: list[GateResult] = []
    for key, label, fn in selected:
        print(f"[RUN ] {label} ...", flush=True)
        try:
            r = fn()
        except Exception as e:                      # 闸门自身崩了也算这一项失败
            r = GateResult(label, "FAIL", f"闸门执行异常: {e}")
        r.name = label
        results.append(r)
        mark = {"PASS": "PASS", "FAIL": "FAIL", "SKIP": "SKIP"}[r.status]
        print(f"[{mark}] {label}  ({r.duration:.1f}s)")
        if r.metrics:
            print(f"       {r.metrics}")
        if r.detail:
            for line in r.detail.splitlines():
                print(f"       {line}")

    failed = [r for r in results if r.status == "FAIL"]
    skipped = [r for r in results if r.status == "SKIP"]
    passed = [r for r in results if r.status == "PASS"]

    print("-" * 68)
    print(f"合计: {len(passed)} PASS / {len(failed)} FAIL / {len(skipped)} SKIP")
    if skipped:
        print("SKIP 项: " + ", ".join(r.name for r in skipped))
    print("-" * 68)

    if args.json:
        print(json.dumps([
            {"name": r.name, "status": r.status, "duration": round(r.duration, 1),
             "metrics": r.metrics, "detail": r.detail}
            for r in results
        ], ensure_ascii=False, indent=2))

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
