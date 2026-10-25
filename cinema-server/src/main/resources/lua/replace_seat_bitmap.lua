-- 原子替换场次座位位图: KEYS[1]=临时 lock 位图, KEYS[2]=临时 sold 位图,
-- KEYS[3]=正式 lock 位图, KEYS[4]=正式 sold 位图
-- ARGV[1]=lock 座位数 N, ARGV[2..N+1]=lock 座位索引,
-- ARGV[N+2]=sold 座位数 M, ARGV[N+3..N+M+2]=sold 座位索引
--
-- 为什么不能「先 DEL 正式位图, 再 SETBIT 建回来」:
--   两次调用之间正式位图是空的, 此刻任何一次锁座都会看到「座位可选」→
--   已经付款的座位被再次卖出 = 超卖。而重建本身还可能失败(Redis 抖动 / OOM),
--   失败后位图就永久停在「空」状态 —— 整场次座位全部看起来可选。
--
-- 所以改成: 先在**临时 key** 上把位图建好, 成功后再一次性换名(RENAME)。
-- 换名之前正式位图原封不动 —— 重建失败 = 座位保持「已锁/已售」(宁可锁死也不误放),
-- 绝不会出现「位图被清空」的窗口。
--
-- 两次换名在同一个脚本里: Lua 脚本执行期间不会有其他客户端的命令插进来,
-- 因此不存在「换了一半」的中间态(lock 已换、sold 还没换)。
--
-- 临时 key 必须先物化(即使位数为 0), 否则 RENAME 会因源 key 不存在而报错。
local n = tonumber(ARGV[1])
local m = tonumber(ARGV[n + 2])

redis.call('SET', KEYS[1], '')
redis.call('SET', KEYS[2], '')

for i = 1, n do
    redis.call('SETBIT', KEYS[1], tonumber(ARGV[1 + i]), 1)
end
for j = 1, m do
    redis.call('SETBIT', KEYS[2], tonumber(ARGV[n + 2 + j]), 1)
end

-- 换名: 目标 key 已存在则被覆盖, 不存在则新建。脚本出错时正式位图仍未被动过。
redis.call('RENAME', KEYS[1], KEYS[3])
redis.call('RENAME', KEYS[2], KEYS[4])

return cjson.encode({lock = n, sold = m})
