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