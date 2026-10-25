# 座位事件序号与座位关注（2026-10-19 新增）

座位流的事件序号、权威重拉与座位关注三条链路。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

- **座位事件带全局单调序号**：`SeatEventPublisher` 发布前对 `cinema:seat:event:seq`（`RedisKeys.SEAT_EVENT_SEQ`）做一次 `INCR`，结果写进事件载荷的 `seq`。三个 `publishXxx` 的公开签名没变，所以 `SeatRedisSubscriber`/`SeatWsHandler` 零改动、四个下游 `@Mock` 测试类不受影响。
- **序号是可选字段**：`parseWsMessage` 只在 `seq` 是有限数值时才挂到事件对象上，缺失/非法**剥掉**该字段而不是挂 `seq: 0`。前后端版本不一致时前端退化成纯增量行为。
- **`seq <= 0` 必须走「无序号、总是应用」分支**：服务端 `INCR` 失败时兜底发 `seq=0`，若前端按 `0 > lastSeq[s]` 过滤会**把所有事件静默丢弃**。这是 `filterBySeq` 的第一条断言。
- **序号只能按座位比较**：`stores/seat.ts` 的 `lastSeq` 是**按座位索引寻址的数组**，不是整场次一个值。两个并发请求拿到 seq=5/6，谁先 PUBLISH 不确定；若用整场次单值，迟到的跨座位事件会被判陈旧而整条丢弃，那个座位的状态变更彻底丢失。`tests/stores/seatEventReducer.test.ts` 里有一条专门锁这个的用例。
- **`lastSeq` 在同场次重拉时不得重置**：重置会让重拉前仍在途的旧事件被重新应用，把刚刷新的权威位图改回旧状态。`load()` 只在换场次或 `seatCount` 变化时重建。
- **权威重拉**：`SeatSelect.vue` 在「释放事件之后」与「WS 重连成功之后」各触发一次防抖（500ms）的 `GET /api/sessions/{id}/seat-map`，失败退避 300ms/1s/3s，三次仍失败才在状态条提示。`load(m, {preserveSelection:true})` 不清空用户已选座位，而是逐个复核、只剔除确实被占走的。**序号不能替代它** —— 取序号与发送不原子，理论上仍有一次短暂假可选。
- **座位关注(Seat Watch)**：纯客户端、不占位、不落库、刷新即失，上限 8。命中释放提醒后自动结束一次性关注。判断逻辑全在 `stores/seatWatch.ts` 的纯函数里（`computeToggleWatch` / `computeReleasedWatchHits` / `computeRevalidateSelection`）—— 本仓 vitest 挂不了 SFC，只能这么测。
- **位图重建走「合并（只加不减）」**（`merge_seat_bitmap.lua`）：`SeatBitmapGuard.forceRecover` 与冷启动重建都靠它。**合并结果 = 正式位图 OR DB 快照**，座位只可能变不可选、绝不可能变可选（宁可锁死也不误放）。三代各自踩过一个坑：①「先删正式位图再重建」在两次 Redis 调用之间留下空位图窗口 —— 此刻的锁座会把已付款座位当成可选 = 超卖，重建失败时位图还会永久停在空状态；②「临时 key + 原子 RENAME」挡住了失败那道口，却漏了并发那道口 —— DB 快照在脚本之外查，快照与换名之间新产生的锁会被覆盖抹掉 = 同一座位两张待支付单 = 超卖；③ 现在合并与换名在**同一个脚本**里原子完成，快照之后新产生的锁一定已落在正式位图里，OR 之后被完整保留。代价：位图不再自动收缩，泄漏位交给离线工具 `tools/reconcile_seat_bitmap.py --fix`。锁这条不变量的用例在 `SeatBitmapGuardTest` 与 `SeatLuaServiceBitmapRoundTripTest`（后者含**超卖窗口的回归用例**：合并不得抹掉快照之后新产生的锁）。
- **关注标识只用角标字形**（`.seat.watched::after`），不改座位背景/描边色。`themeTokens.test.ts` 有一条契约断言专门守这条：引入第 6 种座位色会与既有 5 态争抢明度层级，把「去色后仍可分辨」的间隔压回 1 级。
- Seat locking/paying/cancelling are decorated with `@RateLimit` + `@Idempotent` on the server; the frontend must handle the resulting error codes (e.g., refresh seat map on `40002`).
- **Vite dev server binds IPv6 `[::1]` only** — probing `http://127.0.0.1:5173` returns connection-refused and looks like the frontend is down when it is actually fine. Use `http://localhost:5173`. `Test-NetConnection -Port 5173` can also report False while the server is up.
- **Session listing filters by date; the frontend only ever asks for today/tomorrow/day-after** — `GET /api/movies/{movieId}/sessions` without `?date=` returns an **empty array** unless sessions exist today. `MovieDetail.vue` builds `dateOptions` from `dayjs().add(0|1|2,'day')` and defaults `selectedDate` to **tomorrow**, so if the DB has no sessions in that 3-day window every movie page degrades to `<el-empty>暂无场次` and the seat-selection demo is undemoable. This is data, not a broken endpoint.
- **⚠️ Do NOT re-run `sql/04_extra_demo_data.sql` to refresh demo sessions — it fails silently.** Its deterministic IDs are `movie_id*1e7 + hall_id*1e5 + d*100 + s` where `d` is the **relative day offset** (1..14), with no absolute date in the key. Once it has run, every `d` is already occupied; re-running later recomputes the *same* IDs, and its `INSERT IGNORE` drops every row — **the script reports success while inserting nothing**. Use `sql/05_refresh_sessions_for_demo.sql` instead: it encodes `DAYOFMONTH*100 + slot` (absolute-date IDs), is genuinely idempotent (same day → same IDs → all skipped), and generates 3 days × 4 slots for every `status=1` movie with `id<1000` into halls 1/2. Verified 2026-10-11: 897 → 1077 sessions, 60/day × 3 days, 12 per movie.
- **New sessions need no Redis bitmap warm-up** — `SeatService.seatMap` reads via `readWithPadding(key, seatCount)`, so a session with no Redis key simply returns an all-zero (fully selectable) seat map. Don't hand-write `SETBIT` to "prepare" a fresh session.
- **Seed only halls 1 and 2** — they are the demo halls with `seat` rows. The **live demo DB** now has 240 / 140 seats (1 号厅 20×12, 2 号厅 10×14), but `sql/02_init_data.sql` seeds 1 号厅 as 8×12 = **96** — the demo hall's rows/cols were changed through the admin CRUD, and any hall can change again. Halls 3 and the VIP box carry snowflake IDs and are test artifacts.
- Business/architecture details live in `docs/实现方案.md` and `docs/superpowers/specs/`. Root `README.md` has the full API table, directory map, and verified end-to-end behavior.
- `test/`, root `test_*.py`, `_report*`, logs, and `application-dev.yml` are gitignored local artifacts — not source of truth.
- E2E/browser work is done via the `playwright-cli` skill.
