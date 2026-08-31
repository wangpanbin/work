-- KEYS[1]=lock bitmap, KEYS[2]=sold bitmap
-- ARGV[1]=N (count of lock seat indexes)
-- ARGV[2..N+1]=lock seat indexes
-- ARGV[N+2]=M (count of sold seat indexes)
-- ARGV[N+3..N+M+2]=sold seat indexes
-- 原子批量恢复位图: 用于 P5 冷启动重建
local lockKey = KEYS[1]
local soldKey = KEYS[2]
local n = tonumber(ARGV[1])
for i = 1, n do
    redis.call('SETBIT', lockKey, tonumber(ARGV[1 + i]), 1)
end
local m = tonumber(ARGV[1 + n + 1])
for j = 1, m do
    redis.call('SETBIT', soldKey, tonumber(ARGV[1 + n + 1 + j]), 1)
end
return cjson.encode({lock = n, sold = m})
