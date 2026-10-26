# AGENTS.md

Course-design project: cinema ticket flash-sale + seat-selection. Two collaborating modules in this repo:

- `cinema-server/` — Spring Boot 3.5 backend (JDK 21, MyBatis-Plus, Redis 8 + Lua, WebSocket), port **8080**
- `cinema-web/` — Vue 3 + Vite + TS + Element Plus + Pinia frontend, port **5173**

## Running locally

The frontend is useless on its own. `pnpm dev` proxies `/api` and `/ws` to `http://localhost:8080`, so the backend + MySQL + Redis must be up first.

1. Init DB (one-time): `mysql -uroot -p < sql/01_schema.sql`, `02_init_data.sql`, `03_p0_increment.sql` (in repo root `sql/`). Optional: `04_extra_demo_data.sql` (12 more demo movies + sessions; needs `--default-character-set=utf8mb4`), `05_refresh_sessions_for_demo.sql` (**before any demo/screenshot**: regenerates today+1+2 sessions; idempotent), `06_qa_knowledge.sql` (FAQ knowledge base for the chat assistant — **the table won't exist and `searchFaq` degrades until you run it and restart**), `07_real_demo_movies.sql` (真实影片名 + 专属海报; needs `--default-character-set=utf8mb4`), `08_chat_assistant_log.sql` (对话助手的**写审计 + 用量**表; 不跑它 → 审计写入被 try/catch 吞掉(不影响下单)但管理端「助手用量」看板是空的)。
2. Backend: `cd cinema-server && copy src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml` then edit in MySQL password. File is gitignored — do not commit it. Start with `mvn spring-boot:run`.
3. Frontend: `cd cinema-web && pnpm install && pnpm dev` → http://localhost:5173
4. **对话助手(可选)**: 设 `DEEPSEEK_API_KEY` 环境变量 → 后端启动时 `cinema.chat.api-key` 从 `${DEEPSEEK_API_KEY:}` 解析(`application.yml` 已配)。未设时 `/api/chat/message` 返 `50000` + "对话功能未配置"(这是设计:不挂外部 key 也能让 server 起来);设了之后接通 LLM,前端 ChatWidget 可用。USER 级 setx 即可,cmd/PowerShell 需重启继承。

Local middleware: MySQL 8 (local), Redis 8 at **127.0.0.1:6379**. No Docker/RabbitMQ — the delayed-close uses a Redis ZSet delay queue.

Test accounts seeded by backend on startup: `user1/123456`, `user2/123456`, `admin/123456`.

## Commands

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`) · `pnpm test` (vitest 3.x, `tests/**/*.test.ts`,**737 用例跨 47 个 `.test.ts` 文件**,2026-10-26 实测)。**用例清单不要抄进这份文件** —— 那是环境的一份副本,必然过期(本轮就因为它漂了 3 次);要看有哪些测试,直接看 `cinema-web/tests/` 目录树,数字以 `pnpm test` 的输出为准,文档闸门第 ② 项会核对。主题/换肤闸门见下方「主题 token」「双主题」「SFC 编译闸门」「管理端是控制台」。
- Backend: `mvn spring-boot:run` · `mvn test` (**361 个 JUnit5+Mockito 用例跨 51 个测试类**,2026-10-25 实测)。同样**别把测试类清单抄进这份文件** —— 要看有哪些类,看 `cinema-server/src/test/java/` 目录树;数字以 surefire 实测为准(聚合 `cinema-server/target/surefire-reports/TEST-*.xml`),文档闸门第 ② 项会核对。**测座位/退票/位图链路必须带 `SeatBitmapGuardTest` + `SeatLuaServiceBitmapRoundTripTest`**。
- There is no linter configured in either module. **build 要单独验**:`pnpm build` 与 `pnpm type-check` 查出的问题不重叠(SCSS 变量/模板编译错误只有 build 报)。
- Python 工具: `python -m unittest discover -s tools -p "test_*.py"`(**85 个用例**,2026-10-25 实测;对账脚本的纯逻辑 + 真实 Redis 往返 + 临时 MySQL 库的集成测试 + 文档一致性闸门)。
- **一键闸门**:`python tools/run_all_gates.py` —— 后端 / 前端类型 / 前端构建 / 前端用例 / Python 工具 / 审计红线,逐项 PASS/FAIL + 汇总,任一失败非零退出(`--only` / `--skip` 可挑闸门,`--json` 机器可读)。**它不碰 CI/CD 配置,也不装 git 钩子。**
- **浏览器回归**:`python test/e2e/e2e_regression.py` —— 25 个场景的 Python Playwright 套件(**已入库**)。前置:后端 8080 + 前端 5173 在跑,且跑过 `sql/05_refresh_sessions_for_demo.sql`(场次过期会 404)。
  - **三态**:PASS / FAIL / **SKIP**。SKIP = 当前环境不具备验证条件,**不是通过**。退出码只看 FAIL。报告写 `test/e2e/RESULTS.md`,截图写 `test/e2e/screenshots/`(不入库)。`--only S22` 可单跑调试。
  - **S22 不再必然 SKIP**:后端以 `cinema.chat.provider=stub` 启动时助手**必定出卡**,没出卡记 FAIL(真模型下仍可能 SKIP —— 出不出卡取决于模型当天发挥)。
- **一键演示**:`python tools/demo_run.py` —— 幂等补种 → 全链路冒烟 → 250 并发防超卖 → 三场景压测 → 出报告。默认**不重置**数据;`--reset` 默认 dry-run,还要 `--yes` 才真删(只删演示范围,不碰 `movie`/`hall`)。
- **数字以实测为准**:README/AGENTS 里的用例数会随测试增长漂移,别照抄文档 —— 跑一次 `mvn test`(或聚合 `target/surefire-reports/TEST-*.xml`)、`pnpm test` 和上面的 unittest 取真值再更新。**而且有闸门兜着**:`tools/test_docs_consistency.py` 核对十件事(引用可解析 · 引用必须已入库 · 裸文件名必须存在 · 后端用例数=surefire 实测 · 前端与 Python 数字跨文档一致 · Python 数字=绝对值 · 列表项行首 · `.gitignore` 否定规则不能是死的 · `AGENTS.md` 体积 ≤ 32768 字节 · 历史文档豁免)。改完测试数**别只改一个文档**(2026-10-25 那轮后端从 309 变 312,一次要改 8 个地方)。反过来,文档里**不要重复写数字** —— 重复的数字就是缓存,缓存必然过期;要引用就指向 `AGENTS.md`。

## Conventions & gotchas

- **主题色一律走 `src/styles/main.css` 的 `:root` token,禁止在 `.vue` 里写死色值**。`tests/styles/themeTokens.test.ts` 用静态扫描锁这条:扫 `src/**` 的 `*.vue` / `*.ts`(先剥注释)找 `#rrggbb` 与 `rgba(数字`,白名单有且只有两类:① **自定义属性声明行**(`--x: #hex`,即 token 定义本身——`SeatSelect.vue` 的 `--seat-*`、`AdminHome.vue` 的 `--aside-rule` 属此类,能引用 `var(--hall-*)` 等 :root token 就别手抄);② `Payment.vue` 二维码三处(`#fff` / `#111827` / `#ffffff`,扫码硬要求)。白名单外直接判红。**改色只改 token,不要去改组件。**
- **换主题的最小改动面就是 `:root` 那几组 token** —— `Dashboard.vue` / `Login.vue` / `AdminHome.vue` 已全部 token 化,主色一改,折线图、面积渐变、票房柱、选座高亮、header chip 全站自动跟随。当前色板(2026-10-19 视觉重构后):主色 `#2563eb`(唯一**全局**强调色)、内容区冷灰白 `#eef1f6`、墨色 `#0f172a`、管理端侧栏深靛蓝 `#0f172a`(与座位图暗场 `--hall` 同值,构成一个暗色家族),外加**每条业务域一支的场景身份色**(`--scene-browse` 橙红 / `--scene-seat` 青),见下方「场景身份色」。
- **双主题(2026-10-20 新增,「票根浅色 / 影院暗色」两套 + 三态开关)**:
  - **暗色值写在 `html[data-theme='dark']` 作用域里,绝对不能写进 `:root`** —— `themeTokens.test.ts` 的 `parseRootTokens()` 只匹配 `:root { }`,暗色值进了 `:root` 会被合并、断言只测到最后一套 → **闸门静默失效**。现在闸门用 `parseScopedTokens(css, scope)` 按作用域分别取,断言参数化跑亮/暗两遍。
  - 亮色作用域仍是 `:root`(兜底),暗色作用域是 `html[data-theme='dark']`(特异性 0,1,1 > `:root` 0,1,0)。`main.ts` 里 `element-plus/theme-chalk/dark/css-vars.css` 必须夹在 `dist/index.css` 之后、`main.css` 之前 —— 我们自己的 `--el-*` 暗色覆盖要赢过 EP 的暗色默认色板。
  - **模式无关的 token 只在 `:root` 声明一次**(`--hall*` 家族 / 字体 / 圆角 / 过渡)。暗色作用域里重复声明同值 = 复制粘贴的温床,闸门会判红。
  - **`--hall` 一个字都不能改** —— 座位 5 态实测相邻灰度间隔 `[2,2,24,9]`,**最小值恰好 2、零余量**;`locked` 态是 `transparent`(直接透出面板底色),改 `--hall` 会同时挪动 locked/sold/avail 三个值 → 间隔立刻跌破 2。暗色模式新增的是 `--hall-deep`(#0a0f1a,管理端侧栏用)。
  - **主题模块在 `src/theme/`**(`resolve.ts` 纯函数 / `apply.ts` 写 DOM / `index.ts` 运行时)+ `stores/theme.ts`。偏好存 `localStorage['cinema_theme']`,三态 `auto|light|dark`。
  - **首帧防白闪靠 `index.html` `<head>` 里的同步内联脚本**(Vue 挂载前跑完,读 `localStorage` + `matchMedia` → 设 `data-theme` 与 `dark` 类名)。`resolveTheme` 的解析逻辑在 `theme/resolve.ts` 与那段脚本里**各有一份**(故意重复:模块还没加载,首帧等不起它)。
  - ⚠️ **`readStoredPref()` 省略参数时必须回落到 `globalThis.localStorage`** —— 写成 `storage?.getItem(...)` 的话,省略参数时 `storage` 是 undefined,永远读到 undefined、偏好恒为 `auto`(用户选了暗色也不生效)。2026-10-20 就是这么踩的,当时的单测只测了「显式传 storage」的路径所以没抓到。
- **场景身份色(2026-10-19 新增,2026-10-20 扩到 4 族,`cinema-web/DESIGN.md` 的 The Scene Ink Rule)**:全局印色仍然只有一支, 但每条业务域另有一支自己的身份色, 只在该域的**小面积局部元素**上出现(徽标/圆点/进度条/图表系列/票面印章/图标底色)。当前四族:`--scene-browse`(橙红,域 = `Home`/`MovieDetail`)、`--scene-seat`(青,域 = `SeatSelect`/`SeatItem`)、`--scene-order`(紫罗兰,域 = `OrderList`)、`--scene-payment`(深品红,域 = `Payment`), 每族恰三档 `base`/`ink`/`rgb`。
  - **禁入全局强调面**(主 CTA / 链接 / 焦点环 / 卡片与表格描边 / 导航激活态)与**禁出本域**, 两条都由 `themeTokens.test.ts` 的新 describe 断言 —— 加第二支色时别用 `--accent-` 前缀(既有断言数 `--accent*` 的条数), 也不要用改既有断言的方式绕过。**例外: 章节墨条取所属域的身份色**(2026-10-20)—— 它是「这一屏属于哪个业务域」的标记, 满屏蓝墨条正是「一片蓝」的来源之一; `main.css` 里的全局默认仍是 `--accent`(无域身份的页面如登录/404 用), 各域在自己的 `.vue` 里覆盖, 闸门断言覆盖用的是本域身份色且不越域。
  - `base` 只做图形, **不得用作正文文字**; base 底上的字用「与它对比度更高的那支墨色」(浅 base → `--ink`, 深 base → `--ink-inverse`), 实测 ≥4.5:1 —— 固定用某一支是错的, 深色族(order / payment)用 `--ink` 只有 3.1:1。
  - `ink` 必须在 `--paper`/`--paper-raised`/`--paper-sunk`/管理端纸面四种底上都 ≥4.5:1(实测 `#9a3412` 6.45:1、`#155e75` 6.41:1、`#5b21b6` 8.84:1、`#701a75` 8.84:1)。
  - 身份色与 `--accent` 的**色相差 ≥25°**; 与**同域**语义色必须可分辨 = **色相差 ≥25° 或 灰阶差 ≥25 或 饱和度差 ≥0.25** —— 色相通道不能省: 语义色都是低饱和深色, 任何饱和中调色(品红/紫)与它们的灰阶差和饱和度差都 <25/<0.25, 只认后两个通道的话永远过不了(实测品红 vs `--danger`: 灰阶差 5~20、饱和度差 0.00~0.11)。
  - **同域语义色集合是从代码扫出来的**(域内文件里的 `var(--ok/--warn/--danger)`) **加上** el-tag `type="success|warning|danger"` 的间接引用(静态登记, 因为状态→tagType 是动态映射, 推导不出来) —— 域里新增一个语义色用法, 闸门会立刻知道。
- **SFC 编译闸门(2026-10-20 新增)**:本仓 vitest 是 `environment: 'node'`、没装 jsdom / `@vue/test-utils`,**压根不编译 `.vue`** —— 所以「`.vue` 里少一个 `}`」这类语法错,`pnpm test` 是**全绿**的,只有 `pnpm build` 报(`[vite:vue] Unexpected token`)。2026-10-20 就这么漏过去一个提交快照,差点进历史。`tests/styles/sfcCompile.test.ts` 现在把这条检查搬进测试套件:用 `vue/compiler-sfc` 的 `compileScript` 真编译每个 `<script setup>` 块(**注意 `parse()` 抓不到 script 块的语法错,它只切 SFC 结构;必须走 `compileScript()`**)。**改动 `.vue` 的 script 块后,`pnpm test` 变红就是语法错,不用等 build 才炸。** 这条闸门只覆盖 JS/TS 语法与 SFC 结构,不覆盖 CSS 级联与渲染结果 —— 那仍然只能靠 `pnpm build` + 真浏览器。
- **管理端是控制台, 不设页面身份色(2026-10-20)**:`views/admin/**` 的颜色分工是 **数据墨**(`--ink` 系: KPI 数字/表格数字/ID/轴标签)、**交互墨**(`--accent`: 按钮/链接/焦点环/导航激活)、**图表墨**(`--accent`: 折线/面积/排行条/占比条)、**状态色**(`--ok`/`--warn`/`--danger`)、**结构色**(`--hall` 家族 + `--rule`)。**管理端一个 `--scene-*` 都不许用**(`themeTokens.test.ts` 有断言)—— 色轮已被「全局印色 + 4 族身份色 + 3 支语义色」占满, 再加第五支必然撞色。**层级靠结构不靠颜色**: 头条卡更宽(1.5fr vs 1fr) + 左侧 3px 印色条, 数字一律墨色。管理端也**不套票根符号** —— 它是密集报表, 齿孔/票号是装饰干扰。**管理端参与主题切换(2026-10-20)**: 暗色下管理端纸面交给暗场家族三档(侧栏 `--hall-deep` #0a0f1a 最深 < 页面 `--hall` #0f172a < 卡片 `--hall-raised` #16203a), 侧栏靠 `html[data-theme='dark'] .aside { --aside-bg: var(--hall-deep) }` 下沉一档保住「午夜侧栏」身份 —— 但**颜色纪律一条没松**: 切主题只改明度, 管理端仍然零 `--scene-*`、仍然只抬升不改色。
- **CSS 注释不嵌套 —— 内层的「星号+斜杠」会提前结束外层注释**(`tests/styles/cssContract.test.ts` 有闸门):CSS 与 TS 的块注释都不嵌套, 注释里再写一个注释, 内层的结束符就关掉了外层, 后面的文本被当成 CSS/代码解析 —— 解析器一路跳到下一个右花括号, **整条规则被静默吞掉, 控制台一声不吭**。2026-10-20 就这么把 `.card-reveal` 整条规则吃掉了(症状: 悬停抬升完全不起作用, 而 `pnpm test` / `pnpm build` 全绿)。
- **入场动画的 `both` 填充实态会吃掉悬停位移**(`cssContract.test.ts` 有闸门):层叠里 **animation 来源排在普通作者声明之前**, 所以 `animation: fadeInUp ... both` 在动画结束后**仍然持有**关键帧的最终 `transform: translateY(0)`, 悬停规则里的 `transform: translateY(-2px)` 永远轮不到。拖尾进场要用 **`backwards`**(延迟期间保持起始态, 结束后把 transform 还给元素)。2026-10-20 之前 `Home` / `MovieDetail` / `OrderList` 三处卡片的悬停抬升一直是死的。
- **⚠️ 改色必须同时改 `cinema-web/DESIGN.md` 的 front-matter 与 `src/styles/main.css`** —— 两者存的是同一份色板, `tests/styles/designDocSync.test.ts` 会逐值校验并判红(这是为了堵住「文档说有, 代码没有」的静默漂移)。**DESIGN.md 正文里也不能出现不存在的色值**(同一个闸门会扫正文里的 hex —— 写「被否决的候选色」时别写具体 hex)。`DESIGN.md` front-matter 的 `spacing` 段标了 `docOnly: true`(间距 token 至今不存在于 CSS, 间距仍是各 `.vue` 内写死的 px)。**双主题后 front-matter 的 `colors` 段拆成三块**:`light` 对 `:root`、`dark` 对 `html[data-theme='dark']`、`shared` 是模式无关的那批(暗场家族)—— 闸门逐块比对,`shared` 里任何一项出现在暗色作用域都会判红。
- **座位图的拖拽连选是纯视图层实现**(`utils/seatDragRange.ts` 纯函数 + `SeatItem` 的 `pointerdown`/`pointerenter` 两个 emit + `SeatSelect` 的组件本地 ref), 落座仍走既有的 `seatStore.toggle(idx)` —— **`stores/seat.ts` 一行没改**, 上限/冲突/乐观更新全由 store 说了算。预览环**只画 `outline`**: 不得新增 `--seat-*` 变量(themeTokens 断言那 13 个, 多一个就红), 也不得改座位底色(5 态灰度契约锁的是底色)。区间只取**同行连续可选**, 撞到不可选座位即截断; 落座前再核一次当前状态(拖拽期间 WS 可能已经改过)。**悬停也预演**(2026-10-20): 不按鼠标、只划过同一排也会画出连选区间(锚点 = 本轮划过的第一个座位, 换行即换锚点), 复用同一个纯函数, **只画 outline、绝不落座**; 区间不足 2 座时不画(单座不需要预演, 跟着鼠标闪圈是噪声)。
- **⚠️ `rgba(var(--x-rgb), a)` 是非法 CSS,浏览器会静默丢弃整条声明**(2026-10-12 踩过:侧栏激活态底色因此变 transparent,页面看着像没上色)。`rgba()` 的逗号形式要求 R/G/B 也用逗号分隔,而 token 里存的是空格分隔。**正确写法是斜杠语法 `rgb(var(--x-rgb) / a)`**。themeTokens.test.ts 有一条断言专门锁这个。
- **座位图 5 态转灰度后必须仍可分辨** —— 这是 `SeatSelect.vue` 的既有验收硬要求,现已固化成 `themeTokens.test.ts` 里的可执行断言(相邻灰度差 ≥ 2)。**改 `--seat-*` 任何一色前先跑这条**;实测 `--seat-mine-bg: #2f6b4f` 动一下间隔就掉到 1。
- **中文注释句尾一律用全角 `。`,不用半角 `.`**(2026-10-19 统一)。仓库主流风格是全角(统一前 809 行全角 vs 255 行半角,45 个文件内部两种混用)。**只管中文句尾,技术性 `.` 一个都不能动**:`.java` / `.ts` / `.xlsx` 等扩展名、`45.00` 小数、`v0.1.0` 版本号、`§4.5` 式引用、`e.g.` / `i.e.`、纯英文句末的 `.`、以及 `... . 复杂后缀` 这类。落地做法:**只替换注释正文最后一个 `.`**,不要正则全量替换 `.` → `。`。写文件时注意 `Read` 工具渲染的 `。` 可能实际是 ASCII `.`,锚点请用 Python `repr()` 验字节。
  > **2026-10-19 复盘(两条教训)**:① 第一轮清扫只覆盖 `src/main` + `cinema-web`,`cinema-server/src/test/**` 一个字没动,而这里却写下了「全仓 0 处」——**承诺必须小于等于事实**,否则这条规则本身就是一句假话;② 清扫是**一次性**的,清扫之后新写的文件又带回了半角(本轮 6 处:3 处新测试的 javadoc 首行 + 3 处 system prompt),所以「清扫已完成」不等于「以后都干净」,**新文件仍要自己写对**。补扫后全仓 tracked 文件只剩 3 处半角句尾,全在 AGENTS.md 自己的英文句子里(按约定豁免)。

- **前端 vitest 跑在 `environment: 'node'`, 且没装 jsdom / `@vue/test-utils`**(`vite.config.ts` 的 `test` 段 + `package.json` devDependencies)。**挂不了 Vue 组件**, 涉及视图层的测试只有三条路: ① 把判断逻辑下沉到纯函数/composable 测(如 `usePosterFallback`); ② 对 `.vue` 源码做静态契约断言(`tests/i18n/key-coverage.test.ts` 扫 `t()`、`tests/views/posterWiring.test.ts` 断言 `@error` 接线, 都是这一类, 脆性写在文件头注释里); ③ 上 Playwright 驱动真浏览器。**jsdom 也测不出真实渲染** —— `white-space` 这类 CSS 级联结果在 node/jsdom 下都取不到, 只能靠浏览器验证, 别用静态断言冒充渲染验证。也别为了"能挂组件"随手改 `environment`, 会波及全部 43 个测试文件。

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`. **`_denied` 的唯一消费者是 `App.vue` 的 watcher**(弹 `app.adminDenied` 提示后用 `router.replace` 把参数摘掉)——删掉那个 watcher 就会退回"静默弹回首页"。
- **对话助手的身份参数一律服务端注入**:`ChatTools` 的任何 `@Tool` 方法都**不允许**出现 `userId` 形参,身份取自 `UserContext`。`@P` 参数由 LLM 合成,交给模型填 userId 就是 IDOR 越权(见 `test/e2e-report-2026-09-29.md` P0-1,匿名曾可读全站订单)。`ChatToolsStructureTest#noToolExposesUserIdParam()` 用反射锁死这条红线。
- **一键下单的座位只走服务端快照**:对话助手确认下单时,座位与价格来自 Redis 里的提案快照(`cinema:chat:proposal:{id}`),**前端与模型都不回传座位**。改动这条链路时别把座位加进确认请求体 —— 那等于把 ADR-0002 当初反对的「模型决定锁哪个座」又放回来。`ChatMessage.vue` 的 v-for key 要用 `card.cardId ?? \`${card.sessionId}-${idx}\`` —— 装配器丢卡后索引会位移,用稳定 key 才不会让 Vue 复用错 DOM。
- **位图三脚本语义不可混用**:`release_seat.lua` 只清"未售出的锁定位"(待支付单取消/超时用)· `confirm_seat.lua` 置 sold 并同步清 lock · **`refund_seat.lua` 同时清 sold+lock**(退票专用)。退票误用 `releaseSeats` 会导致座位 sold 残留、永久不可再售(P1-1)。改任一脚本后请跑一次"锁座→支付→退票→重新锁座"位图解码回归。
- **⚠️ 位图重建必须走「合并(只加不减)」**(`merge_seat_bitmap.lua`):**合并结果 = 正式位图 OR DB 快照**,合并与换名在**同一个脚本**里原子完成 → **座位只可能变不可选,绝不可能变可选**(宁可锁死也不误放)。不能改回「先删后建」(留空位图窗口 = 超卖),也不能改回「用快照 RENAME 覆盖」(抹掉快照与换名之间新产生的锁 = 超卖)。三代各自的坑与代价详见 `docs/agents/seat-flow.md`。改这条链路请同时跑 `SeatBitmapGuardTest` + `SeatLuaServiceBitmapRoundTripTest`(后者含**超卖窗口的回归用例**:合并不得抹掉快照之后新产生的锁)。
- **退票释放的幂等键不能退化成全局共享 key**:`SeatWrite.Paid` 的 `orderNo` 为空时,`cinema:order:seat-released:{orderNo}` 会变成一把**所有订单共用**的锁 —— 任何一张单的退票都会把其它单的释放一起挡掉(座位永久占着 = 漏卖),而且不报错。构造器对空 `orderNo` 直接抛 `IllegalArgumentException`(record 做不到编译期检查,是运行期 fail-fast)。
- **⚠️ Redis 位图的位序是「字节内 MSB 优先」** —— 字节 `0xC0` 的第 0、1 位为 1(不是第 6、7 位)。seat-map API 的 `lockBitmap` 与 `utils/bitmap.ts`(`bit = 7 - (i & 7)`)用的就是同一套编码。2026-10-19 写对账工具时按 LSB 解,把 2 个真位读成 224 个假位,差点把「压测脚本污染」当成「全站漂移」。`redis-cli` 取位图的两个坑见 `docs/agents/local-env.md`。
- **位图与订单状态的对账工具**:`python tools/reconcile_seat_bitmap.py` —— 比对 Redis 位图与 DB 里 PENDING_PAY/PAID 订单的座位,报「泄漏(位图占着但 DB 无有效订单)」与「缺失(DB 说该占但位图是空的)」。默认只查有订单的场次,`--include-orphan` 连孤儿位图一起看,`--fix` 按 DB 权威清掉泄漏位。**位图漂移只能靠它发现** —— 肉眼在 140 座的图上看不出来。
- **锁座失败/冲突的提示权在调用方,不在 axios 拦截器**(`api/request.ts` 的 `skipErrorToast`):拦截器无条件弹一次通用文案,业务层再弹一次本地化的,同一次失败就弹两条(而且通用那条是后端中文原文,英文界面就夹中文)。自己弹提示的请求必须声明 `skipErrorToast: true`。通用错误文案统一走 `i18n/errorText.ts`(中文界面透出后端原文,其它界面用本地化兜底)。
- **⚠️ 401 不许无条件跳登录页**: 跳转判定见 `utils/authRedirect.ts`(守卫与拦截器同源),公开页静默降级;失效走 `utils/authSession.ts`(localStorage 与 Pinia store 一起清)。
- **⚠️ 同场次重复锁座:「关旧单 / 锁新座」的顺序决定用户会不会两头落空** —— 按座位有无交集分两条路,见 `docs/agents/seat-flow.md`。
- **⚠️ 选座页同路由换场次必须重新装载**(Vue Router 4 复用组件实例,漏了是资损),见 `docs/agents/seat-flow.md`。
- **⚠️ 影厅 VIP 起始行在 `seat` 表不在 `hall` 表**,后端 update 的 null = 「保持原样」不是「清空」,见 `docs/agents/seat-flow.md`。
- **WS 座位事件不带发起人**(`SeatEventPublisher` 只发 type/seats/seq),所以客户端无法从事件本身区分「我锁的」与「别人抢的」。做法是**锁座请求发出前先认领**(`seatStore.claimMyLocked`),让自家锁座的 WS 事件早到也不算冲突;reducer 的 `computeApplyEvent` 收了 `myLockedSet` 参数来做这个区分。漏了认领就会弹「你已选的 N 个座位被他人锁定」(2026-10-19 联调 F2)。
- **`seatIndexes` 必须按场次校验范围, 上界不能用常量** — `LockSeatsDTO` 的 `@Max(4095)` 只是兜底, 真实上界是场次的 `seatCount`, 由 `OrderCore#requireSeatsInRange` 在进 Lua 之前拦住(本机演示库的行列值会变,别抄 —— 见 `docs/agents/seat-flow.md`)。**越界索引不会被业务拒绝, 而是直接落进 `lock_seat.lua` 的 `SETBIT`** —— Redis bitmap 按 offset 立即分配且永不回收(实测 offset=100000 把位图从 18B 撑到 12501B, 公开的 `GET /seat-map` 响应放大 61 倍; 硬上界 2^32-1 时单 key 512MiB)。`SessionBitmapReader.readWithPadding` 另有读侧截断兜底历史脏位图。改动这条链路请同时跑 `OrderCoreSeatRangeTest` + `LockSeatsDTOValidationTest` + `test/stability_edge_probe.py`。
- **`_denied` / E2E 证据文件**:2026-09-29 的 E2E 报告见 `test/e2e-report-2026-09-29.md`(14 个 bug 已全部修复,`git log` 可查对应 commit)—— 2026-10-25 起它**随仓库入库**。浏览器回归套件在 `test/e2e/e2e_regression.py`(同样入库),跑完会刷新 `test/e2e/RESULTS.md`;截图在 `test/e2e/screenshots/`(**不入库**,随时重生成)。**文档里不要引用没入库的路径** —— 新克隆的仓库拿不到,`tools/test_docs_consistency.py` 的第 5 项检查会判红。
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, invalidates the session (localStorage keys **and** the Pinia store) and redirects to `/login` **only when the current route requires login**.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40003` 对话下单提案已过期/已被消费, `40004` 对话下单功能被回滚闸门关掉, `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
  - **`40002` 一码多义是踩过的坑** —— 提案过期原本也用 `40002`,前端按 spec §5.3 的表把过期显示成「座位被抢走了」。现已拆出 `40003`/`40004`,前端按 `err.code` 分支(映射表 `CONFIRM_ERROR_KEYS` 在 `components/chat/orderProposalCard.ts`,有单测兜底)。
  - **`request.ts` 的 40101/40102 分支也要挂 `err.code`** —— 那条分支提前 `Promise.reject(new Error(body.msg))`,不挂 `.code` 的话,上层所有 `code === 40101` 的判断都走不到(确认卡的「请先登录后再下单」就是这么变成死代码的)。其它码走通用分支(`err.code = body.code`)所以正常。
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
- **⚠️ `ws.ts` 的 `connect()` 绝不能无条件写 `status='connecting'`**（2026-10-19 浏览器验收实测出的真 bug）。`SeatSelect.vue` 靠 `s === 'open' && prev === 'closed'` 判定重连并触发权威重拉；`connect()` 每次都先写 `'connecting'`，那个判定**永远为假** —— 断连期间丢失的事件一次都不会被补偿，用户会一直看着一张过期座位图而毫无察觉（状态条照常显示「实时同步中」，但一条 `GET /seat-map` 都不会发）。修法是纯函数 `statusOnConnect(everOpened)`：首连返回 `'connecting'`，重连保持 `'closed'`。**改动 `ws.ts` 的状态赋值时，先跑 `tests/utils/ws.test.ts` 的「重连态必须与首连态不同」。**
- **⚠️ 助手的写审计表 `chat_assistant_log` 是「助手没越权写」的证据,不是可选日志**:`ChatAssistantLogService` 写库**全程 try/catch,失败不影响主流程**;**红线指标恒为 0**(`kind='WRITE_CONFIRM' AND confirmed_by_user=0` 的行数)—— 助手没有任何写工具,写入口只有 `ChatOrderController#confirm` 一处。**将来新增任何写入口,必须显式传 `confirmed_by_user=1`**,否则这条查询会立刻数出来。**用量未知记 null 不记 0**(0 会被误读成「免费」);成本是**估算值**,单价在 `application.yml` 的 `cinema.chat.cost.*`,**需人工维护**。细节见 `docs/agents/chat-assistant.md`。
- **助手只有三种卡**:`SEAT_SUGGESTION`(只读建议)· `ORDER_PROPOSAL`(一键下单确认卡,ADR-0004)· `ORDER_GUIDE`(2026-10-25 新增的**取消/退票引导卡**)。引导卡**只带跳转信息**(`guideRoute` + `guideAction`),**不带任何可提交载荷** —— 助手**不执行**取消或退票,只把用户引导到订单页自己操作(ADR-0002:助手不写)。新增卡型要同时改 `components/chat/cardRegistry.ts` 的 `CARD_KINDS` 与 `CARD_TYPE_LABEL_KEYS`,否则 `cardRegistry.test.ts` 判红。
- **⚠️ 提示词与卡片解析器必须对同一件事说同样的话**:`ORDER_PROPOSAL` 的 `seatIndexes` 是**可选**的(座位由服务端重读实时位图决定,ADR-0004 D8)—— 提示词里**不许**再要求「必须先调 `findContiguousSeats` 才出卡」:那会让模型在一晚多个候选场次时反问「看哪一场」,出卡链路死锁(2026-10-25 实测,三次真实调用全部 `cards=0`)。改 `CinemaAssistant` 的提示词前先跑 `CinemaAssistantPromptTest`。
- **假 LLM 开关**:`cinema.chat.provider=stub`(env `CINEMA_CHAT_PROVIDER`)→ 后端用确定性夹具代替真模型,**只替换「模型决策」这一步**,卡片解析/提案装配/座位重读/前端渲染全是生产代码。`GET /api/chat/status` 暴露当前 provider,浏览器回归据此把「没出卡」分成 SKIP(真模型)还是 FAIL(stub)。细节见 `docs/agents/chat-assistant.md`。
- **四套闸门有一个一键入口**:`python tools/run_all_gates.py`(逐项 PASS/FAIL + 汇总 + 非零退出;`--only`/`--skip` 挑闸门,`--json` 机器可读;`--with-e2e` 带上浏览器回归)。**它不碰 CI/CD 配置,也不装 git 钩子**(用户红线)。**改完任何用例数都要重跑它** —— 文档里的数字由 `tools/test_docs_consistency.py` 兜着。
- **同一用户同一场次只能有一个待支付单**（`RedisKeys.userPending(userId, sessionId)`，TTL=支付窗口 900s）。用 user2 造测试数据时，**第二笔锁座会把前一笔待支付单顶掉并自动取消**，先建的那几单 `status` 立刻变 2（已取消）、座位跟着释放。做「多座位同时释放」之类的验收必须**一笔订单锁 4 个座位**（`POST /orders/lock` 上限 4 座），不能一单一座地循环。另外 `@RateLimit` 会在连打 5~6 次 `POST /orders/lock` 后返回 `42900`，脚本里要留间隔。

## 专题文档

以下细节按主题拆到了 `docs/agents/`。**红线主体(跨主题的硬约束)仍在上面的
`Conventions & gotchas` 一节** —— 拆出去的是「按主题展开的细节」, 不是「可以忽略的规则」。
改到对应主题前先读对应文件:

| 文件 | 覆盖内容 | 什么时候读 |
| --- | --- | --- |
| `docs/agents/seat-flow.md` | 座位事件序号 · 权威重拉 · 座位关注 | 改 `stores/seat.ts` / `SeatSelect.vue` / WS 事件协议 |
| `docs/agents/chat-assistant.md` | 对话助手的只读工具 · 一键下单提案 · 回滚闸门 | 改 `ChatTools` / `OrderProposal*` / 提案链路 |
| `docs/agents/frontend-i18n-a11y.md` | 中英文双语 · locale key 覆盖闸门 | 改 `i18n/locales/*.ts` 或新增 `t()` 调用 |
| `docs/agents/architecture-map.md` | 前端目录地图 · 核心文件位置 | 找文件时 |
| `docs/agents/tooling.md` | issue tracker · triage 标签 · 领域词表 | 用 `gh` CLI / 写探针脚本时 |
| `docs/agents/local-env.md` | 本机环境 · 查库/删文件/编码姿势 | 查本机数据、跑 PowerShell 命令、装依赖时 |
