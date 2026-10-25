# 本机环境与工具姿势

本机怎么跑、怎么查、哪些姿势会静默出错。**按主题展开,不覆盖仓库级底线** —— 硬约束仍在 `AGENTS.md` 的 `Conventions & gotchas` 一节。

## 浏览器回归套件的环境前置

`test/e2e/e2e_regression.py` 是 Python Playwright 套件。**本机装它要三步**,少一步都会在跑之前就挂:

```powershell
# ① playwright 本体(本机已有) + 它依赖的 pyee —— ⚠️ 版本必须 <14:
#    playwright 1.63 要 pyee<14, 装成 15 会报 'No module named pyee' 之类的怪错
python -m pip install -i https://mirrors.aliyun.com/pypi/simple "pyee<14,>=13"

# ② 浏览器二进制(playwright 本体装了不代表浏览器装了)—— 走国内镜像
$env:PLAYWRIGHT_DOWNLOAD_HOST="https://cdn.npmmirror.com/binaries/playwright"
python -m playwright install chromium

# ③ 起服务再跑(套件要后端 8080 + 前端 5173, 且跑过 sql/05_refresh_sessions_for_demo.sql)
python test/e2e/e2e_regression.py
```

> 三个坑的共同点:**依赖装了 ≠ 能跑**。playwright 的浏览器二进制是单独下的一份,
> 默认从 playwright.azureedge.net 拉(本机慢),所以要走 `PLAYWRIGHT_DOWNLOAD_HOST` 换镜像。
## 查本机数据库 —— 用 `tools/db_query.py`,别手敲 mysql

```bash
python tools/db_query.py "SELECT id, name, seat_rows, seat_cols, seat_count FROM hall ORDER BY id"
python tools/db_query.py --db cinema --table hall      # 打印建表语句
```

连接信息按 **环境变量 > 命令行 > `application-dev.yml` > 内置默认值** 取。**口令永不落进任何入库文件** —— 它从 gitignore 的 `cinema-server/src/main/resources/application-dev.yml` 里读(与 `tools/reconcile_seat_bitmap.py` 同一套读法),所以新克隆的仓库要先按 `AGENTS.md` 把 `application-dev.yml.example` 复制成 `application-dev.yml`;临时库用 `CINEMA_DB_*` 环境变量指过去。**只读**:语句必须以 `SELECT/SHOW/DESCRIBE/EXPLAIN/WITH` 开头,任何写操作关键字(含 `INTO OUTFILE`)一律拒绝 —— 写操作请走带 `--fix` 开关的专用工具。

**为什么别手敲**:紧凑写法 `mysql -h127.0.0.1 -uroot -p123456 ...` 会被 PowerShell 拆坏,mysql 判定为 usage 错误后**打印整页帮助** —— 几千行噪声,真正的错误被埋掉。正确姿势是**参数与值之间留空格**(`-h 127.0.0.1 -P 3306 -u root -p<pwd> -D <db> -B -e "<sql>"`),`tools/db_query.py` 把它固化了,`tools/reconcile_seat_bitmap.py` 的 `_mysql_cmd()` 也是同一套。

## git 遇到中文路径 —— 记得关掉 `core.quotepath`

`git ls-files` 默认把非 ASCII 路径转义成八进制(`"\346\211\213..."`)。于是

```bash
git ls-files docs | grep 压测        # 什么都搜不到 —— 路径已被转义
```

**看起来像「文件没入库」,其实文件就在库里** —— 2026-10-25 差点据此做错决策(把已跟踪的 `docs/压测报告.md` 当成缺失文件,加了多余的 gitignore 否定规则)。

正确姿势:

```bash
git -c core.quotepath=false ls-files
git -c core.quotepath=false status --short
```

要按名字过滤就直接用 `git -c core.quotepath=false ls-files -- <glob>`,别拿输出再喂给 `Select-String`。

## `.scratch/` —— 临时脚本区,不入库

`.scratch/` 已被 `.gitignore` 忽略,放**一次性的**探针/迁移/统计脚本(本仓的 `split_agents.py` / `backfill_*.py` / `check_doc_links.py` 都在这儿)。

⚠️ **但凡有长期价值的检查,别留在这儿** —— 目录被清一次就没了。2026-10-25 的文档引用检查器就是这么差点丢掉的,现已收进 `tools/test_docs_consistency.py`,随 `python -m unittest discover -s tools` 一起跑。

## PowerShell 控制台编码

每条命令开头固定加:

```powershell
$env:PYTHONIOENCODING='utf-8'; [Console]::OutputEncoding=[Text.Encoding]::UTF8
```

控制台默认 GBK,少了它 `print` 会抛 `UnicodeEncodeError`,`Select-String` 会把中文显示成 `?`(看起来像文件坏了)。批量命令末尾显式 `exit 0` —— 循环里最后一条命令的退出码会被当成整段的成败。

**多行 python 一律写成 `.py` 文件再跑**:PowerShell here-string 管道喂给 python 会被控制台编码吞掉非 ASCII;`python -c "多行脚本"` 里的 `\"` 与 shell 的转义规则叠加,报错还指不到真因。内联只放不含反斜杠、不含 `$` 的单行语句。

## 删文件

用可恢复删除,不要永久删:

```bash
rm -- "相对路径"        # Windows: runtime 路由到受信任的 launcher
```

⚠️ **不要在同一条命令里先 `Set-Location` 再 `rm`** —— 本地安全策略会拦(「write/delete targets cannot be verified safely after a command working directory change」)。把 `rm` 单独放在一条命令里,路径相对会话工作目录。

## `redis-cli` 取位图的两个坑

位图是**原始字节**,不是文本 —— 用 `redis-cli` 直接看会骗你:

- `--no-raw` 给的是**转义文本**(带引号),不是字节 —— 位图看着像 `"@@..."`。
- `--raw` 给原始字节,但**会补一个行尾换行** —— 凭空多出 2 个位,解码出来全是假的。

正确姿势:`STRLEN` 取真实字节数,再按这个长度截断(别信 `--raw` 的输出长度)。
位图的位序是「**字节内 MSB 优先**」(字节 `0xC0` 的第 0、1 位为 1,不是第 6、7 位)——
详见 `AGENTS.md` 的对应规则条。

## 新增文件在提交前会让文档闸门报「引用必须已入库」

`tools/test_docs_consistency.py` 的第 ⑤ 项检查「文档里引用的文件必须在 `git ls-files` 里」。
**新建的文件在 commit 之前不在 `git ls-files` 里** —— 于是文档一引用新模块/新测试文件,
闸门立刻报「文档引用了没入库的文件」, 看着像文档写错了, 其实是文件还没进 index。

**姿势**:新增文件后先 `git add`(只是进暂存区, **不等于提交**), 闸门就能看见它们了。
真正的提交仍然由你决定(`AGENTS.md` 的规矩是**不自动 commit / push**)。

## 依赖安装

走国内镜像源,默认落 F 盘:`pip` 用 `https://mirrors.aliyun.com/pypi/simple`,npm/pnpm 用 `https://registry.npmmirror.com/`,maven 用 `https://maven.aliyun.com/repository/public`,cargo 用 `https://rsproxy.cn`。

**只改本次安装命令的参数**,不执行 `npm config set registry` / `pip config set` / `conda config --set`,也不写用户级环境变量。

## 对话助手本机跑的是 **stub**,不是真 LLM

本机已把 `CINEMA_CHAT_PROVIDER` 设成**用户级** `stub`(2026-10-25),所以:

- `mvn spring-boot:run` 起的助手**不接 DeepSeek**,用确定性夹具回话 —— 演示与回归可重复,
  S22(对话浮窗 → 下单卡 → 确认下单)必定 PASS。
- **别把它当成「模型今天不想出卡」**:stub 是代码,行为固定。它出什么卡、什么措辞都能预期。
- 想临时切回真模型(只影响当前 shell):`$env:CINEMA_CHAT_PROVIDER='deepseek'`。
  想永久切回:`setx CINEMA_CHAT_PROVIDER deepseek`。
- 当前 provider 随时可查:`GET /api/chat/status` → `{"provider":"stub"|"deepseek","enabled":bool}`。

⚠️ `setx` 只对**新开的**进程生效 —— 已经开着的 cmd / PowerShell 窗口不会继承,
所以改完要重开终端(或在当前窗口手动 `$env:...` 覆盖)。

## 本机环境基线

Windows 11 · JDK 21 · Maven 3.9.15 · Node 24 · MySQL 8.0.42(`127.0.0.1:3306`)· Redis 8.8.0(`127.0.0.1:6379`)· 无 Docker / 无 RabbitMQ(延迟关单用 Redis ZSet)。
