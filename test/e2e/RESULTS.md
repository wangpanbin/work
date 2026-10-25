# E2E 回归测试报告 (Python Playwright)

> 执行时间: 2026-10-25 14:03 · Chromium headless · 前端 5173 / 后端 8080
> 目的: 上轮修复 Bug A(WS 推送)/Bug B(myLockedSeats)/Bug C(snowflake ID) 后的全场景回归

| 场景 | 状态 | 证据 |
| --- | :-: | --- |
| S12 健康/静态端点 | ✅ PASS | /actuator/health=200 UP; /swagger-ui.html=302 |
| S01 公共浏览+snowflake直达 | ✅ PASS | 首页 20 张影片卡; 卡0详情含 6 个场次; snowflake id 直达详情 OK(接口 id 为 string) |
| S02 登录(错密+成功) | ✅ PASS | 错密提示=有; user1 token 已写入 localStorage |
| S03 选座+myLocked+WS实时推送 | ✅ PASS | user1 UI锁座7→支付页(orderNo=211423607919…); 自锁座位显示 locked_mine ✓; user2 API锁[14, 15] → user1 无刷新 WS 更新 locked_other 1→+2 ✓ |
| S04 支付+取消+座位释放 | ✅ PASS | 订单93513985 支付→status=1 paidAt=2026-10-25T14:02:22; 订单89146882 取消→status=2; 座位[12, 16]已释放(lockBits清零) |
| S05 超时关单(5s扫描器) | ✅ PASS | 订单33353473 DB+ZSet 置过期 → 5s 内 status=2, 座位12已释放(lockBits清零) |
| S06 订单列表筛选 | ✅ PASS | 全部=20, 待支付=0, 已支付=20, 已取消=20, 分页口径一致 ✓(每页 20 条) |
| S08 权限边界(40301/admin) | ✅ PASS | user1 → code=40301 (UI 落点 http://localhost:5173/, toast=有); admin → API 200 + UI 影片表格可见 |
| S09 影片CRUD(snowflake回归) | ✅ PASS | rows 20→新增(E2E回归测试片140242, id=2114236191…, str)→搜索→编辑时长99→105→删除, snowflake ID 全链路 ✓ |
| S10 影厅CRUD+座位自动生成 | ✅ PASS | 影厅列表 8→+1 (E2E回归厅140247, id=2114236216628273153); DB seat 表自动生成 5×10=50 条 ✓ |
| S11 场次管理+新场次座位图 | ✅ PASS | 场次列表 20 行; 对话框字段完整; API 建场次 id=2114236223649538050 → seat-map 50 座 10 列 ✓; 测试数据清理=是 |
| S13 选座首拉失败+重试 | ✅ PASS | 首拉失败 → 错误态 + 文案 + 重试按钮 + WS 仍在连; 撤销拦截后点重试 → 240 个座位出现 ✓ |
| S14 管理端场次分页 | ✅ PASS | 分页器在位; 分页端点 total=1617, 首页 5 条 ✓ |
| S15 管理端恢复位图按钮 | ✅ PASS | 「恢复位图」按钮在位、点击有反馈 ✓ |
| S16 对话一键下单+写审计 | ✅ PASS | 提案→确认→订单 18288898 建成; 审计留痕 1 条(confirmed_by_user=1); 红线未确认写数=0 ✓ |
| S17 座位关注+释放提醒 | ✅ PASS | 关注徽标出现 ✓; 对方取消 → 释放提醒 toast 出现 ✓ |
| S18 暗色主题切换 | ✅ PASS | data-theme light → dark; 偏好已存 localStorage=dark ✓ |
| S19 中英双语切换 | ✅ PASS | lang zh-CN → en-US; 英文界面无中文残留 ✓ |
| S20 位图合并不抹掉已锁座位 | ✅ PASS | 重建后座位 12 仍被锁定(lockBit=1) ✓ —— 合并语义生效, 没有超卖窗口 |
| S07 登出+路由守卫 | ✅ PASS | 退出→跳 /login 且 token 清除; 未登录访问 /orders 被重定向回 /login ✓ |

**统计**: ✅ 20 / ❌ 0
