# 影院/演出抢票选座系统

课程设计项目：Spring Boot 3 + Redis Bitmap + 延迟关单 + WebSocket 实时选座。

- 架构与实现细节见 [docs/实现方案.md](docs/实现方案.md)
- 原始方案见 [plan.md](plan.md)

## 技术栈

| 端 | 技术 |
| --- | --- |
| 后端 | JDK 21 · Spring Boot 3.5 · MyBatis-Plus · Redis(Bitmap/Lua/ZSet延迟队列) · JWT |
| 前端 | Vue 3 · Vite · TypeScript · Element Plus · Pinia · Axios · dayjs |
| 中间件 | MySQL 8（本机服务）· Redis 8（本机服务） |

> 本机无 Docker/RabbitMQ：延迟关单采用 **Redis ZSet 延迟队列**（接口抽象，RabbitMQ 为扩展实现，见方案文档 §5）。

## 快速开始

### 1. 初始化数据库（一次性）

```bash
mysql -uroot -p < sql/01_schema.sql
mysql -uroot -p < sql/02_init_data.sql
```

### 2. 配置并启动后端（8080）

```bash
cd cinema-server
# 首次: 复制配置模板并填入 MySQL 密码
cp src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml
#   编辑 application-dev.yml, 将 "<MYSQL_PASSWORD>" 改为真实密码 (该文件已被 .gitignore 排除)

mvn spring-boot:run
```

- 接口文档: http://localhost:8080/swagger-ui.html
- 测试账号: `user1 / 123456`、`user2 / 123456`（后端启动时自动初始化）

### 3. 启动前端（5173）

```bash
cd cinema-web
pnpm install
pnpm dev
```

打开 http://localhost:5173 ，登录后浏览影片/场次（选座购票 W2 开放）。

## 目录结构

```
├── docs/            设计文档（实现方案/api/压测报告）
├── sql/             建表 + 演示数据
├── cinema-server/   Spring Boot 后端（按领域分包）
├── cinema-web/      Vue3 前端
└── test/jmeter/     压测脚本（W5）
```

## 开发进度

- [x] W1 环境适配、建表、骨架（认证/影片/场次）、前端骨架
- [x] W2 座位图接口 + Redis Bitmap + Lua 锁座 + 下单
- [x] W3 模拟支付 + ZSet 超时关单（5s 扫描器 + 1min 补偿）+ WebSocket 推送
- [x] W4 前端选座/支付/订单页 + 路由联通
- [x] W5 管理端 CRUD（电影/影厅/场次）+ Python+JMeter 压测三场景 + docs/压测报告.md

## 端到端验证（W2/W3 已实测通过）

- 200/250 并发抢同场次同一座位：0 超卖，任意瞬间同座位最多 1 张待支付单 ✓
- 锁座冲突：返回 `code=40002` + `data.conflict=[...]`，前端自动刷新座位图 ✓
- 支付：DB CAS 成功 → Lua confirm_seat (lock→sold) ✓
- 主动取消：DB CAS + Lua release_seat ✓
- 超时关单：把 expire_at + ZSet score 同步调为过去 → 5 秒内主链路扫描器释放座位 ✓
- 兜底补偿：仅改 DB expire_at（不同步 ZSet）→ 1 分钟内补偿任务兜底释放 ✓
- WebSocket：`/ws/seat/{sessionId}` HTTP 101 握手成功 ✓
- 经 Vite 代理（前端 5173 → 后端 8080）调用全部接口 ✓
- 管理端权限：role=1 放行 / role=0 返回 40301 ✓
- 管理端 CRUD：影片/影厅/场次的增删改查与座位自动生成 ✓

## 压测结果（W5，详见 docs/压测报告.md）

| 场景 | 并发 | 总请求 | QPS | P99 | 错误率 |
| --- | --- | --- | --- | --- | --- |
| A 座位图读 | 100×10s | 18,333 | **1833** | 65ms | 0% |
| B 锁座抢购 | 200×10s | 15,933 | 1593 | 108ms | 0%(业务冲突除外) |
| C 混合 | 200×10s | 14,953 | 1495 | 83ms | 0%(业务冲突除外) |

**核心防超卖结论**（250 并发抢同一座位）：任意瞬间同座位最多 1 张待支付单，0 超卖。

## 测试账号

- 普通用户: `user1 / 123456`, `user2 / 123456`
- 管理员: `admin / 123456`

## W5 文件

- `test/load_test.py` — 三场景 Python 压测驱动
- `test/concurrency_strict.py` — 防超卖专项
- `test/jmeter/scenario-*.jmx` — JMeter 模板
- `docs/压测报告.md` — 完整报告
