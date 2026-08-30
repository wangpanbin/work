-- 释放: KEYS[1]=lock位图, KEYS[2]=sold位图; ARGV[1..]=座位索引
-- 只清未售出的锁定位(已售座位不可被超时释放)
local released = {}
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', KEYS[2], seat) == 0 then
        redis.call('SETBIT', KEYS[1], seat, 0)
        table.insert(released, seat)
    end
end
return '[' .. table.concat(released, ',') .. ']'
