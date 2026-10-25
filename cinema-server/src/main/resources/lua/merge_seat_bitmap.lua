-- 合并 DB 座位快照到场次位图(只加不减): KEYS[1]=正式 lock 位图, KEYS[2]=正式 sold 位图,
-- KEYS[3]=DB 快照 lock, KEYS[4]=DB 快照 sold, KEYS[5]=合并结果 lock, KEYS[6]=合并结果 sold
-- ARGV[1]=lock 座位数 N, ARGV[2..N+1]=lock 座位索引,
-- ARGV[N+2]=sold 座位数 M, ARGV[N+3..N+M+2]=sold 座位索引
--
-- 合并结果 = 正式位图 OR DB 快照 —— **只加不减**。
--
-- 为什么是「合并」而不是「替换」(2026-10-25 二次评审发现, 详见 docs/发布说明-v0.4.0.md):
--   旧实现是「把 DB 快照 RENAME 覆盖正式位图」。但 DB 快照是在**脚本之外**取的:
--   SeatBitmapRebuilder 查库 → 拼快照 → 才进本脚本。两者之间有一次网络往返。
--   那段时间里任何一次锁座都会把位写进正式位图, 而这份锁**不在快照里**
--   (订单行可能还没提交) → RENAME 覆盖后那把锁被抹掉 → 座位回落为「可选」
--   → 另一人可以锁到同一座位 → 同一座位两张待支付单 = **超卖**。
--   冷启动路径(ensureBitmaps)尤其危险: 位图刚被清掉, 所有用户同时抢座,
--   窗口内的每一把锁都会被抹掉。
--
-- 为什么不做成「先 DEL 正式位图, 再 SETBIT 建回来」:
--   两次调用之间正式位图是空的, 此刻任何一次锁座都会看到「座位可选」
--   → 已付款的座位被再次卖出 = 超卖; 重建本身失败时位图还会永久停在「空」状态。
--
-- 现在的语义:
--   ① 合并结果 = 正式位图 OR DB 快照 → **只可能让座位变不可选, 绝不让座位变可选**。
--      宁可锁死也不误放 —— 这是整套位图设计的头号原则。
--   ② 合并与换名在**同一个脚本**里: Lua 执行期间不会有别的客户端命令插进来,
--      所以「快照之后新产生的锁」一定已经落在正式位图里, OR 之后被完整保留。
--      换名之前正式位图原封不动 —— 脚本失败 = 座位保持原状。
--   ③ 代价: 位图**不再自动收缩**。高位泄漏位(越界索引撑大的体积)靠
--      `tools/reconcile_seat_bitmap.py --fix` 离线清理(它走的是「DEL + 按合法位重建」)。
--      本脚本不做这件事 —— 在线重建只负责「补齐」, 不负责「瘦身」。
--
-- 临时 key 必须先物化(即使位数为 0), 否则 RENAME 会因源 key 不存在而报错。
local n = tonumber(ARGV[1])
local m = tonumber(ARGV[n + 2])

redis.call('SET', KEYS[3], '')
redis.call('SET', KEYS[4], '')

for i = 1, n do
    redis.call('SETBIT', KEYS[3], tonumber(ARGV[1 + i]), 1)
end
for j = 1, m do
    redis.call('SETBIT', KEYS[4], tonumber(ARGV[n + 2 + j]), 1)
end

-- BITOP OR: dest = src1 | src2。源 key 不存在时按「全 0 空串」处理。
-- dest 必须与源不同, 所以合并结果另用一个临时 key。
redis.call('BITOP', 'OR', KEYS[5], KEYS[1], KEYS[3])
redis.call('BITOP', 'OR', KEYS[6], KEYS[2], KEYS[4])

-- ⚠️ 必须把合并结果物化: Redis **不存空串** —— BITOP 在「所有源都是空串」时
-- 不会创建 dest(而是把 dest 删掉)。若此时直接 RENAME, 会报 "no such key"。
-- 而 Lua 脚本**出错不回滚** —— 前面的合并已经落进正式位图了, 报错只会留下半换名状态。
-- 所以这里先补一次 SET, 保证两个合并结果都存在, 后面的两次 RENAME 才不会失败。
if redis.call('EXISTS', KEYS[5]) == 0 then redis.call('SET', KEYS[5], '') end
if redis.call('EXISTS', KEYS[6]) == 0 then redis.call('SET', KEYS[6], '') end

-- 换名: 目标 key 已存在则被覆盖, 不存在则新建。脚本出错时正式位图仍未被动过。
redis.call('RENAME', KEYS[5], KEYS[1])
redis.call('RENAME', KEYS[6], KEYS[2])

-- 快照临时 key 用完即删(合并结果已换名成正式 key)
redis.call('DEL', KEYS[3])
redis.call('DEL', KEYS[4])

return cjson.encode({lock = n, sold = m})
