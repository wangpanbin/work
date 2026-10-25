# Agent skills

issue tracker、triage 标签与领域词表的位置。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

## 闸门入口

`python tools/run_all_gates.py` —— 后端 / 前端类型 / 前端构建 / 前端用例 / Python 工具 / 审计红线,
逐项 PASS/FAIL + 汇总,任一失败非零退出。`--only` / `--skip` 挑闸门,`--json` 机器可读。

**它不碰 CI/CD 配置,也不装 git 钩子** —— 那是用户红线,别加。

`--with-e2e` 会带上浏览器回归(`test/e2e/e2e_regression.py`),它需要后端 8080 与前端 5173
都在跑;服务没起就**明确报缺前置**,不静默跳过。

## Issue tracker

GitHub Issues (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

## Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

## Domain docs

Single-context layout. See `docs/agents/domain.md`. `CONTEXT.md` currently covers the **revenue-export** vocabulary plus the **seat-selection** terms (座位索引 / 选座 / 锁座 / 锁座冲突 / 待支付订单 / 场次上下文 / 行动卡片). Decisions live in `docs/adr/`.

## Local environment & tool ergonomics

本机怎么跑、怎么查、哪些姿势会静默出错(查库用 `tools/db_query.py`、git 中文路径要关 `core.quotepath`、`.scratch/` 的定位、PowerShell 编码前缀、删文件的姿势)。See `docs/agents/local-env.md`.
