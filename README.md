# 影院/演出抢票选座系统

课程设计项目：**Spring Boot 3 + Redis Bitmap + Lua 锁座 + 延迟关单 + WebSocket 实时选座 + 经营数据大屏**。

- 架构与实现细节见 [docs/实现方案.md](docs/实现方案.md)
- P0 增量设计 spec: [docs/superpowers/specs/2026-08-31-p0-increment-design.md](docs/superpowers/specs/2026-08-31-p0-increment-design.md)
- 原始方案见 [plan.md](plan.md)

---

## 项目亮点

| 维度 | 实现 |
| --- | --- |
| **防超卖** | Redis Bitmap + Lua 原子锁座;DB CAS + 生成列唯一索引双保险;250 并发抢同一座位 0 超卖 |
| **实时同步** | WebSocket `/ws/seat/{sid}` + Redis Pub/Sub 跨实例广播 |
| **延迟关单** | Redis ZSet 延迟队列(主链路 5s 扫描) + 定时补偿任务(1min 兜底),双保险 |
| **冷启动守护** | 服务重启/Redis flush 后锁座前自动从 DB 重建 Bitmap,杜绝"看似可选实则已售" |
| **交易闭环** | 锁座 → 支付 → 退票(状态机 PAID→REFUNDING→REFUNDED) + 电子票(HMAC 签名,24h 过期,一次性验票) |
| **工程严谨** | 22 个 JUnit5 + Mockito 单元测试覆盖核心链路;`@RateLimit`/`@Idempotent` AOP 注解;OrderService 拆 4 service + 1 core(单文件 ≤ 300 行) |
| **数据可视化** | 管理端经营看板(4 卡片 + 3 图 + 1 表);实时数据大屏(WebSocket 推送锁座事件) |
| **前端体验** | 全局路由守卫(未登录/非管理员自动拦截) · 影片搜索/筛选 · 注册确认密码 · 移动端适配 · 座位图重构 |

---

## 功能矩阵

### 用户端

| 功能 | 状态 | 备注 |
| --- | --- | --- |
| 注册/登录(JWT) | ✅ | BCrypt 密码;注册需确认密码;登录态写入 localStorage 供路由守卫读取 |
| 影片列表 + 详情 | ✅ | 包含海报/时长/简介 |
| 影片搜索 + 类型/地区筛选 | ✅ P0 F1 | MySQL LIKE + 精确匹配 |
| 场次选择 | ✅ | 关联影厅+时间 |
| 实时选座(座位图) | ✅ | WebSocket 推送座位变化 |
| 锁座下单(15min 待支付) | ✅ | Lua 原子防超卖 |
| 模拟支付 | ✅ | CAS 防双花 |
| 我的订单 + 状态机 | ✅ | PENDING_PAY/PAID/CANCELLED/REFUNDING/REFUNDED |
| 主动取消 | ✅ | CAS 取消 |
| 退票(模拟退款) | ✅ P0 N1 | 状态机 + 退款日志 + 卡死补偿 |
| 电子票二维码 | ✅ P0 N2 | HMAC 签名 + 24h 过期 + 一次性验票 |

### 管理端

| 功能 | 状态 | 备注 |
| --- | --- | --- |
| 影片/影厅/场次 CRUD | ✅ | 含座位自动生成 |
| 手动恢复位图 | ✅ P0 P5 | `POST /api/admin/sessions/bitmaps/recover` |
| 经营数据看板 | ✅ P0 O3 | 今日票房/订单/趋势/TOP 影片/上座率 |
| 实时数据大屏 | ✅ P0 D1 | WebSocket 推送,`/admin/live` |

### 工程化

| 功能 | 状态 | 备注 |
| --- | --- | --- |
| 接口限流(`@RateLimit`) | ✅ P0 E4 | Redis 滑动窗口 Lua,锁座/支付/退票分级限流 |
| 幂等键(`@Idempotent`) | ✅ P0 E5 | SETNX,锁座/支付前置挡重试 |
| OrderService 拆分 | ✅ P0 E1 | 4 service + 1 core,单文件 ≤ 300 行 |
| 关键路径单测 | ✅ P0 E2 | 22 个 JUnit5 + Mockito 用例 |
| 错误码体系 | ✅ | 0/4xxxx/5xxxx + data 携带附加信息 |

---

## 技术栈

| 端 | 技术 |
| --- | --- |
| 后端 | JDK 21 · Spring Boot 3.5 · MyBatis-Plus 3.5.12 · Redis 8(Bitmap/Lua/ZSet/PubSub) · WebSocket · JWT · AspectJ AOP · HMAC-SHA256 |
| 前端 | Vue 3 · Vite · TypeScript · Element Plus · Pinia · Axios · dayjs |
| 中间件 | MySQL 8(本机) · Redis 8(本机,127.0.0.1:6379) |
| 测试 | JUnit5 · Mockito · AssertJ · Python 压测脚本 · JMeter 5.6.3(可选) |

> 本机无 Docker/RabbitMQ:延迟关单采用 **Redis ZSet 延迟队列**(接口抽象,`DelayQueue` 可替换为 RabbitMQ 实现,见 `docs/实现方案.md §5`)。

---

## 快速开始

### 1. 初始化数据库(一次性)

```bash
mysql -uroot -p < sql/01_schema.sql
mysql -uroot -p < sql/02_init_data.sql
mysql -uroot -p < sql/03_p0_increment.sql   # P0 增量: movie 加列 + refund_log + ticket
# 可选: 更多演示影片(12 部含真实海报) + 未来 14 天场次; 必须带 --default-character-set=utf8mb4
mysql -uroot -p --default-character-set=utf8mb4 < sql/04_extra_demo_data.sql
```

### 2. 配置并启动后端(8080)

```bash
cd cinema-server
# 首次: 复制配置模板并填入 MySQL 密码 + 二维码 HMAC secret
cp src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml
#   编辑 application-dev.yml, 将 <MYSQL_PASSWORD> 改为真实密码
#   (该文件已被 .gitignore 排除)

mvn spring-boot:run
```

- 接口文档: http://localhost:8080/swagger-ui.html
- 测试账号: `user1 / 123456`、`user2 / 123456`、`admin / 123456`(后端启动时自动初始化密码)

### 3. 启动前端(5173)

```bash
cd cinema-web
pnpm install
pnpm dev
```

打开 http://localhost:5173

- 用户: `/login` 注册/登录 → `/` 首页选片/搜索 → `/movie/:id` 详情选场次 → `/seat/:sessionId` 选座/锁座 → `/payment` 支付 → `/orders` 我的订单(取消/退票/电子票)
- 管理员: `/admin` → 经营看板/实时大屏 + 影片/影厅/场次管理
- 路由守卫: 未登录访问 `/seat*`、`/payment`、`/orders` 跳 `/login`(带 redirect);非管理员访问 `/admin*` 跳首页;未知路径显示 404 兜底页

### 4. 跑单测

```bash
cd cinema-server
mvn test
# Tests run: 22, Failures: 0, Errors: 0
```

---

## API 总览

### 用户端

| Method | Path | 说明 | 鉴权 |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | 注册(确认密码由前端校验) | 公开 |
| POST | `/api/auth/login` | 登录(JWT) | 公开 |
| GET | `/api/users/me` | 当前登录用户信息 | 需登录 |
| GET | `/api/movies` | 影片搜索/筛选(`?keyword=&genre=&region=`) | 公开 |
| GET | `/api/movies/{id}` | 影片详情 | 公开 |
| GET | `/api/sessions/movies/{movieId}/sessions` | 影片场次列表 | 公开 |
| GET | `/api/sessions/{sessionId}` | 场次详情 | 公开 |
| GET | `/api/sessions/{sessionId}/seat-map` | 场次座位图 | 公开 |
| POST | `/api/orders/lock` | 锁座下单 | 需登录 + `@RateLimit` + `@Idempotent` |
| POST | `/api/orders/{orderNo}/pay` | 模拟支付 | 需登录 + `@RateLimit` + `@Idempotent` |
| POST | `/api/orders/{orderNo}/cancel` | 主动取消 | 需登录 + `@RateLimit` |
| POST | `/api/orders/{orderNo}/refund` | 退票(P0 N1) | 需登录 + `@RateLimit` + `@Idempotent` |
| GET | `/api/orders/{orderNo}/ticket` | 取电子票(P0 N2) | 需登录 |
| GET | `/api/orders/my` | 我的订单(分页) | 需登录 |
| GET | `/api/orders/{orderNo}` | 订单详情 | 需登录 |
| GET | `/api/tickets/verify` | 验票(公开,P0 N2) | 公开 |

### 管理端

| Method | Path | 说明 | 鉴权 |
| --- | --- | --- | --- |
| CRUD | `/api/admin/movies` | 影片管理 | 管理员 |
| CRUD | `/api/admin/halls` | 影厅管理 | 管理员 |
| CRUD | `/api/admin/sessions` | 场次管理 | 管理员 |
| POST | `/api/admin/sessions/bitmaps/recover?sessionId=` | 手动恢复位图(P0 P5) | 管理员 |
| GET | `/api/admin/dashboard/summary` | 经营看板汇总(P0 O3) | 管理员 |

### WebSocket

| URL | 用途 | 鉴权 |
| --- | --- | --- |
| `/ws/seat/{sessionId}` | 座位变化广播(本场观众) | 公开 |
| `/ws/admin` | 管理端大屏(P0 D1) | 公开(演示用) |

---

## 目录结构

```
F:/test/work/
├── plan.md                              原始架构方案
├── README.md                            本文件
├── docs/
│   ├── 实现方案.md                       详细实现方案(W1-W5 + P0)
│   ├── 压测报告.md                       W5 压测结果
│   ├── 压测报告-v2.md                    P0 后回归压测结果
│   └── superpowers/specs/                P0 增量设计 spec
├── sql/
│   ├── 01_schema.sql                    建库建表
│   ├── 02_init_data.sql                 演示数据(3 部影片 + 3 天场次)
│   ├── 03_p0_increment.sql              P0 增量: movie 扩列 + refund_log + ticket
│   └── 04_extra_demo_data.sql           扩展演示: 12 部影片(带 TMDB 海报) + 14 天场次
├── cinema-server/                       Spring Boot 后端(按领域分包)
│   ├── pom.xml
│   └── src/main/java/com/cinema/
│       ├── CinemaApplication.java
│       ├── common/                       公共(注解/AOP/上下文/异常/加密/限流/响应)
│       │   ├── annotation/              RateLimit, Idempotent
│       │   ├── aop/                     RateLimitAspect, IdempotentAspect
│       │   ├── context/                 UserContext
│       │   ├── crypto/                  HmacSigner
│       │   ├── exception/               BizException + 全局异常处理
│       │   ├── ratelimit/               RedisSlidingWindow
│       │   └── result/                  R + ResultCode(0/4xxxx/5xxxx)
│       ├── config/                       配置(Web/WebSocket/Redis+PubSub/MyBatis/Jackson/OpenApi)
│       ├── interceptor/                  Jwt / AuthRequired / Admin 拦截器
│       ├── modules/                      业务模块(按领域)
│       │   ├── user/         认证 + 用户信息
│       │   ├── movie/        影片(搜索/筛选 F1)
│       │   ├── hall/         影厅
│       │   ├── cinema/       影院
│       │   ├── session/      场次
│       │   ├── seat/         座位图
│       │   ├── order/        订单(E1 拆分: Lock/Pay/Cancel/Query + Core) + 电子票 Ticket
│       │   ├── payment/      模拟支付/退款(MockPayment / MockRefund)
│       │   └── admin/        管理端(CRUD + Dashboard O3)
│       ├── infra/                        基础设施
│       │   ├── delay/        DelayQueue(Redis ZSet 实现)
│       │   ├── jwt/          JwtUtil
│       │   ├── mq/           OrderTimeoutScanner
│       │   ├── redis/        Lua 服务 + SeatBitmapGuard(P5) + cache
│       │   └── ws/           WebSocket(Seat + Admin 大屏 D1)
│       ├── support/                      DataInitializer(测试账号自动初始化)
│       └── job/                          定时任务
│           └── OrderTimeoutCompensateJob  超时关单 + 退款卡死补偿(N1)
└── cinema-web/                          Vue3 前端
    ├── package.json
    └── src/
        ├── api/                          axios 封装 + 各模块 API
        ├── components/                   SeatItem, Countdown
        ├── router/index.ts               路由 + 全局守卫(登录/管理员)
        ├── stores/                       Pinia(user/seat/movieCache)
        ├── styles/main.css               全局样式
        ├── types/                        TS 类型定义
        ├── utils/                        ws 封装, bitmap 解析
        └── views/
            ├── Home.vue                  首页(F1 搜索筛选)
            ├── Login.vue                 登录/注册(确认密码)
            ├── MovieDetail.vue           影片详情 + 场次
            ├── SeatSelect.vue            选座(WebSocket 实时)
            ├── Payment.vue               模拟支付 + 倒计时
            ├── OrderList.vue             我的订单(取消/退票/电子票)
            ├── NotFound.vue              404 兜底
            └── admin/
                ├── AdminHome.vue         后台骨架 + 嵌套路由
                ├── Dashboard.vue         经营看板(O3)
                ├── LiveDashboard.vue     实时大屏(D1)
                ├── MovieManage.vue       影片管理
                ├── HallManage.vue        影厅管理
                └── SessionManage.vue     场次管理
```

---

## 开发进度

- [x] W1 环境适配、建表、骨架(认证/影片/场次)、前端骨架
- [x] W2 座位图接口 + Redis Bitmap + Lua 锁座 + 下单
- [x] W3 模拟支付 + ZSet 超时关单(5s 扫描器 + 1min 补偿) + WebSocket 推送
- [x] W4 前端选座/支付/订单页 + 路由联通
- [x] W5 管理端 CRUD(电影/影厅/场次) + Python+JMeter 压测三场景 + docs/压测报告.md
- [x] P0-1  E1 拆 `OrderService` 为 4 service + 1 core
- [x] P0-2  E2 补 22 个关键路径单测
- [x] P0-3  P5 bitmap 冷启动重建 + 管理端手动恢复
- [x] P0-4  E4 `@RateLimit` 注解 + Redis 滑动窗口
- [x] P0-5  E5 `@Idempotent` 注解 + Redis SETNX
- [x] P0-6  F1 影片搜索 + 类型/地区筛选
- [x] P0-7  N1 退票流程(PAID→REFUNDING→REFUNDED + 补偿)
- [x] P0-8  N2 电子票二维码(HMAC 签名 + 24h 过期 + 一次性)
- [x] P0-9  O3 管理端经营看板
- [x] P0-10 D1 实时数据大屏(/ws/admin)
- [x] UX-1  前端体验升级(全局路由守卫 / 注册确认密码 / 移动端适配 / 座位图重构 / 11 处 UI 修复 + 404 兜底页)

---

## 端到端验证(W2-W5 + P0 已实测通过)

- 200/250 并发抢同场次同一座位:0 超卖,任意瞬间同座位最多 1 张待支付单 ✓
- 锁座冲突:返回 `code=40002` + `data.conflict=[...]`,前端自动刷新座位图 ✓
- 支付:DB CAS 成功 → Lua confirm_seat (lock→sold) ✓
- 主动取消:DB CAS + Lua release_seat ✓
- 超时关单:把 expire_at + ZSet score 同步调为过去 → 5 秒内主链路扫描器释放座位 ✓
- 兜底补偿:仅改 DB expire_at(不同步 ZSet) → 1 分钟内补偿任务兜底释放 ✓
- WebSocket:`/ws/seat/{sessionId}` HTTP 101 握手成功 ✓
- 经 Vite 代理(前端 5173 → 后端 8080)调用全部接口 ✓
- 管理端权限:role=1 放行 / role=0 返回 40301 ✓
- 管理端 CRUD:影片/影厅/场次的增删改查与座位自动生成 ✓
- **P0**: 退票成功 → 座位实时变可选(WebSocket 推 RELEASED) ✓
- **P0**: 电子票扫描 → 二次扫描返"已使用" ✓
- **P0**: 手动恢复位图(模拟 flush)→ 锁座能正确恢复 bitmap ✓
- **P0**: 重复锁座请求(< 3s)→ 第二次返 `code=40900` 幂等冲突 ✓
- **P0**: 100 次同用户锁座 → 第 6 次起返 `code=42900` 限流 ✓
- **P0**: 看板数据与 DB 直接查询一致(随机抽 3 个数对账) ✓
- **P0**: 大屏下单事件 1s 内反映到事件流 ✓
- **UX**: 未登录访问 `/seat*`/`/payment`/`/orders` → 跳 `/login` 并带 redirect ✓
- **UX**: 普通用户访问 `/admin` → 拦截回首页 ✓ | 已登录访问 `/login` → 跳首页 ✓
- **UX**: 注册确认密码不一致 → 前端拦下,不发请求 ✓

---

## 压测结果(W5,详见 docs/压测报告.md)

| 场景 | 并发 | 总请求 | QPS | P99 | 错误率 |
| --- | --- | --- | --- | --- | --- |
| A 座位图读 | 100×10s | 18,333 | **1833** | 65ms | 0% |
| B 锁座抢购 | 200×10s | 15,933 | 1593 | 108ms | 0%(业务冲突除外) |
| C 混合 | 200×10s | 14,953 | 1495 | 83ms | 0%(业务冲突除外) |

**核心防超卖结论**(250 并发抢同一座位):任意瞬间同座位最多 1 张待支付单,0 超卖。

---

## 测试账号

- 普通用户: `user1 / 123456`, `user2 / 123456`
- 管理员: `admin / 123456`

---

## 测试文件

- `cinema-server/src/test/java/...` — 22 个 JUnit5 + Mockito 单元测试(E2)
- `test/load_test.py` — 三场景 Python 压测驱动
- `test/concurrency_strict.py` — 防超卖专项
- `test/concurrency_test.py` — 并发基础压测
- `test/e2e/e2e_regression.py` — E2E 回归用例(含截图产物)
- `test/jmeter/scenario-*.jmx` — JMeter 模板
- `test_mixed.py` — 200 并发混合读/锁/查压测(根目录)
- `test_webapp_e2e.py` — Web 端 E2E 脚本(根目录)
- `docs/压测报告.md` — 完整压测报告(W5)
- `docs/压测报告-v2.md` — P0 后回归压测报告
- `docs/superpowers/specs/2026-08-31-p0-increment-design.md` — P0 增量设计 spec

> `test/` 目录与根目录测试脚本均属本地测试产物,已由 `.gitignore` 排除,不进入版本库。
