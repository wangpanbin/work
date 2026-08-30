-- 锁座: KEYS[1]=lock位图, KEYS[2]=sold位图; ARGV[1..]=座位索引
-- 第一遍检查冲突(任一已售/已锁则整体失败), 第二遍原子锁定 —— 防超卖核心
-- 手工拼接JSON, 避免cjson对空数组输出 {} 导致解析歧义
local lockKey, soldKey = KEYS[1], KEYS[2]
local conflict = {}

for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', soldKey, seat) == 1
       or redis.call('GETBIT', lockKey, seat) == 1 then
        table.insert(conflict, seat)
    end
end

if #conflict > 0 then
    return '{"ok":false,"conflict":[' .. table.concat(conflict, ',') .. ']}'
end

for i = 1, #ARGV do
    redis.call('SETBIT', lockKey, tonumber(ARGV[i]), 1)
end
return '{"ok":true,"conflict":[]}'
