-- 支付确认: KEYS[1]=lock位图, KEYS[2]=sold位图; ARGV[1..]=座位索引
-- 只把仍处于 lock 态的座位置为 sold; 与 release 并发时无论谁先执行结果一致(不双花)
-- 置 sold 后同步清 lock:已售座位不再需要"锁定"语义,留着会让位图长期脏
-- (E2E 2026-09-29 P1-1 连带问题)。lock_seat.lua 同时检查 sold+lock,故清 lock 不会让座位被他人抢走
local confirmed = {}
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', KEYS[1], seat) == 1 then
        redis.call('SETBIT', KEYS[2], seat, 1)
        redis.call('SETBIT', KEYS[1], seat, 0)
        table.insert(confirmed, seat)
    end
end
return '[' .. table.concat(confirmed, ',') .. ']'
