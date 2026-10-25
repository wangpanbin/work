-- 退票释放已售座位: KEYS[1]=lock位图, KEYS[2]=sold位图, KEYS[3]=按订单幂等标记
-- ARGV[1]=标记 TTL 秒, ARGV[2..]=座位索引
--
-- 与 release_seat.lua 的关键区别(见 test/e2e-report-2026-09-29.md P1-1):
--   release_seat 只清"未售出的锁定位"(sold==0 才清),是给待支付单超时/取消用的;
--   已支付订单的座位 sold==1,走 release_seat 等于什么都不清 → 退票后座位永久灰着不可售。
-- 本脚本专治退票:同时清 sold + lock 两个位,让座位真正回到可选池。
--
-- ⚠️ 幂等标记(2026-10-24,防超卖):位图里没有"这张 sold 位属于哪张单"的信息,
-- 所以光看位图判断不了归属。退票主流程「CAS PAID→REFUNDING → 释放座位 → 调渠道退款
-- → CAS REFUNDING→REFUNDED」在中途中断时,订单会停在 REFUNDING 而座位已回到可选池;
-- 补偿任务 5 分钟后再调一次本脚本,若那批座位已被他人买走(sold=1),
-- 第二次无条件清位会把**别人已付款的座位**清成可选 → 同一座位两张 PAID 订单 = 超卖。
--
-- 因此:标记与清位在同一个脚本里原子完成。KEYS[3] 已存在 = 本单座位已释放过,
-- 直接返回空列表,不再碰任何位 —— 无论那批座位此刻归谁。
--
-- 标记是**无条件写入**的(哪怕 released 为空),这是刻意的(2026-10-25 二次评审澄清):
--   若改成「只有真的释放了座位才写标记」, 那么「第一次调用没释放到任何位」之后标记缺失
--   → 补偿任务第二次再调 → 若那批座位此刻已被他人买走(sold=1) → 第二次清位把
--   **别人已付款的座位**清成可选 → 同一座位两张 PAID 订单 = 超卖。
--   也就是说: 条件写标记会把这个脚本**专门**要堵的超卖窗口重新打开。
--   「本次没释放到座位」意味着那些座位本来就不占(或已被他人买走)—— 此时跳过后续释放
--   正是想要的行为(宁可锁住也不误放)。
local ttl = tonumber(ARGV[1])
if not ttl or ttl <= 0 then
    ttl = 86400
end

-- 本单座位已释放过 → 不再重复释放(否则会清掉已被他人买走的 sold 位)
if redis.call('EXISTS', KEYS[3]) == 1 then
    return '[]'
end

local released = {}
for i = 2, #ARGV do
    local seat = tonumber(ARGV[i])
    local wasSold = redis.call('GETBIT', KEYS[2], seat)
    local wasLocked = redis.call('GETBIT', KEYS[1], seat)
    if wasSold == 1 or wasLocked == 1 then
        redis.call('SETBIT', KEYS[2], seat, 0)
        redis.call('SETBIT', KEYS[1], seat, 0)
        table.insert(released, seat)
    end
end

-- 打幂等标记:与上面的清位同一次 EVAL,原子生效
redis.call('SET', KEYS[3], '1')
redis.call('EXPIRE', KEYS[3], ttl)

return '[' .. table.concat(released, ',') .. ']'
