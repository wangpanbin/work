# 词汇表 (CONTEXT)

影院抢票选座系统的术语词典。本文件**只放术语定义，不放实现细节**。改动任何术语前请先回到这里对照。

## 营收 (Revenue)

**毛额口径** = 已支付订单的 `order.total_amount` 之和，限定 `order.status = 1` 且 `paid_at ∈ [from, to]`。

- 不含待支付 (`status=0`) / 已取消 (`status=2`) / 退款中 (`status=3`) / 已退款 (`status=4`) 订单。
- 不从 `order_item.price` 二次聚合（与 `total_amount` 等价但多一跳）。
- "净额" 暂未启用：若需 `毛额 − 成功退款(refund_log.amount, status=0)` 后续单独定义，避免当前报表语义分裂。

## 范围 (Date Range)

营收的统计区间，由两个 ISO 日期 `[from, to]` 决定，按 `paid_at` 落区间判断（含端点）。

- `from` 默认 `today - 6 days`，`to` 默认 `today`。
- `from > to` 视为非法入参，返回业务码 `40001`（与现有入参校验风格一致）。
- 单边 (`from == null && to == null`)：使用 7 日报表。

## 模式 (Mode)

范围选择方式，取字符串。

| Mode | 语义 | 后端落库 |
| --- | --- | --- |
| `preset` | UI 选了「今日 / 7 天 / 30 天 / 本月」之一 | `from`/`to` 由预设算法算出，仍走 `paid_at BETWEEN` |
| `custom` | UI 自定义 [from, to] | 直传两个 ISO 日期 |

## 导出 (Export)

后端以二进制流返回，客户端用 `<a download>` 触发浏览器下载文件。

- Content-Type: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- Content-Disposition: `attachment; filename="营收报表_{from}_至_{to}.xlsx"`
- 业务码 0 时直接是文件流；非 0 时按现有 `{code, msg, data}` JSON 返回。

## 营收明细行 (RevenueRow)

导出一行 = 一个已支付订单。列集合：

| 列 | 来源 |
| --- | --- |
| 订单号 | `order.order_no` |
| 用户名 | `user.username` |
| 影片 | `movie.title` |
| 影厅 | `hall.name` |
| 开场时间 | `session.start_time` |
| 座位数 | `order.seat_count` |
| 订单金额 | `order.total_amount` |
| 支付时间 | `order.paid_at` |
| 订单状态 | `order.status`（恒为 1） |

按 `paid_at DESC` 排序。

## 座位索引 (Seat Index)

影厅内一个座位的**唯一整数编号**，`0 ~ seat_count-1`，同时是该场次两张 Redis 位图的**位序号**。

- 唯一的物理含义载体：Lua 锁座、`order_item.seat_index`、`seat` 表 `uk_hall_seat(hall_id, seat_index)` 全部以它为准。
- 与 `row_no`/`col_no` 是**两套不同坐标**：行列表述（"3排5座"）只用于展示（`seatDesc`），不参与任何判定。

## 选座 (Seat Selection)

用户在座位图上**点击选中、尚未落库**的本地状态。上限 4 个。

- 纯客户端概念：`stores/seat.ts` 的 `selected` 集合。刷新页面即丢失。
- **不等于** 锁座。选中不占位、不产生订单、不被其他用户看到。

## 锁座 (Seat Lock)

把「座位索引」从**可选**变为**已锁**的原子状态变更，成功后**必然**产生一张「待支付订单」。

- 三态判定：`sold位=0 且 lock位=0` 才可选；`lock位=1 且 sold位=0` 即已锁。
- 是抢票链路的唯一写入入口，天然并发敏感（防超卖）。
- 与「选座」严格区分：**选座是意图，锁座是事实**。

## 锁座冲突 (Lock Conflict)

请求锁定的座位集合中**存在任何一个**已锁或已售座位。全部锁定失败，返回冲突座位列表。

- 冲突是**整单失败**语义，不做部分成功——不存在"锁到 3 个、失败 1 个"。

## 待支付订单 (Pending Order)

`order.status = 0` 的订单，持有座位但未付款。**同一用户在同一场次最多存在一张**。

- 唯一性约束 `uk_user_pending(user_id, pending_key)`，其中 `pending_key` 仅当 `status=0` 时取 `session_id`。
- 有支付窗口；窗口结束或用户放弃即离开该状态，座位释放。
- 由锁座产生，只能转为已支付或已取消。

## 场次上下文 (Chat Context)

客户端在发起一轮「对话式订票」请求时**随请求显式携带**的当前浏览事实：所在路由、场次标识、已选座位数、已选座位索引。

- 目的是让"这个还有座吗"这类指代**可确定解析**，而不是让模型从对话历史里猜当前场次。
- 只描述**用户此刻在看什么**，不描述对话历史。

## 行动卡片 (Action Card)

机器人回复中携带的**结构化建议**：目标场次 + 建议座位索引 + 展示用座位描述 + 预估金额。

- **是建议，不是执行**。卡片本身不锁座、不建单、不占用任何座位。
- 真正的「锁座」只能由用户确认后经既有下单入口发生。
- 与聊天文本并列返回，前端渲染为可点击控件；用户点击后进入常规选座流程。