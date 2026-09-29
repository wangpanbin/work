-- 退票释放已售座位: KEYS[1]=lock位图, KEYS[2]=sold位图; ARGV[1..]=座位索引
--
-- 与 release_seat.lua 的关键区别(见 test/e2e-report-2026-09-29.md P1-1):
--   release_seat 只清"未售出的锁定位"(sold==0 才清),是给待支付单超时/取消用的;
--   已支付订单的座位 sold==1,走 release_seat 等于什么都不清 → 退票后座位永久灰着不可售。
-- 本脚本专治退票:同时清 sold + lock 两个位,让座位真正回到可选池。
--
-- 调用前置:OrderPayService.refund() 已完成「订单归属校验 + CAS PAID→REFUNDING」,
-- 所以这里无条件清位是安全的;返回实际发生变化的座位(幂等:重复调用第二次返回空列表)。
local released = {}
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    local wasSold = redis.call('GETBIT', KEYS[2], seat)
    local wasLocked = redis.call('GETBIT', KEYS[1], seat)
    if wasSold == 1 or wasLocked == 1 then
        redis.call('SETBIT', KEYS[2], seat, 0)
        redis.call('SETBIT', KEYS[1], seat, 0)
        table.insert(released, seat)
    end
end
return '[' .. table.concat(released, ',') .. ']'
