-- 支付确认: KEYS[1]=lock位图, KEYS[2]=sold位图; ARGV[1..]=座位索引
-- 只把仍处于 lock 态的座位置为 sold; 与 release 并发时无论谁先执行结果一致(不双花)
local confirmed = {}
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', KEYS[1], seat) == 1 then
        redis.call('SETBIT', KEYS[2], seat, 1)
        table.insert(confirmed, seat)
    end
end
return '[' .. table.concat(confirmed, ',') .. ']'
