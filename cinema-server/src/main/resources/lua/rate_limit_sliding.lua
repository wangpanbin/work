-- KEYS[1]=桶key
-- ARGV[1]=permits, ARGV[2]=window_ms, ARGV[3]=now_ms, ARGV[4]=member
-- 滑动窗口限流: 返回 1 允许, 0 拒绝
local key = KEYS[1]
local p, w, now, m = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), ARGV[4]
redis.call('ZREMRANGEBYSCORE', key, 0, now - w)
local cnt = redis.call('ZCARD', key)
if cnt >= p then return 0 end
redis.call('ZADD', key, now, m)
redis.call('PEXPIRE', key, w)
return 1
