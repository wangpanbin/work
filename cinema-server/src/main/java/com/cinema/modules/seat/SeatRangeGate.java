package com.cinema.modules.seat;

import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.seat.reader.SessionInfoLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 座位索引范围闸门 —— 座位索引落进任何 Lua 之前的<b>唯一</b>一道检查。
 *
 * <p><b>为什么从 {@code OrderCore} 抬出来</b>: 这道闸门保护的是<b>座位位图</b>(infra/redis 的
 * Lua 与位图), 不是订单本身。挂在 {@code OrderCore} 上会让「订单域的核心工具」看起来
 * 拥有位图安全的职责, 而它其实只是借用了 {@code SessionInfoLoader} 拿 seatCount。
 * 抬到 seat 域之后, 谁想动位图就知道该找谁。
 *
 * <p><b>位置不可挪动</b>: 调用点必须保证这道检查发生在
 * {@code lock_seat.lua} / {@code confirm_seat.lua} / {@code refund_seat.lua}
 * <b>之前</b>。越界索引一旦进到 Lua 的 {@code SETBIT}, Redis bitmap 会按 offset
 * <b>立即分配内存且永不回收</b>:
 * <ul>
 *   <li>实测 offset=100000 → 位图 18B 撑到 12501B(694 倍), 公开的 {@code GET /seat-map}
 *       响应从 278B 放大到 16969B(61 倍)</li>
 *   <li>实测 offset=4000000 → 响应 666959B(2399 倍)</li>
 *   <li>SETBIT 硬上界 2^32-1 → 单个 key 分配 512MiB, 实测 Redis used_memory_peak 冲至 641MiB</li>
 * </ul>
 * 由于 {@code /api/auth/register} 公开、位图 key 无 TTL, 这是一条无门槛的远程内存耗尽路径。
 *
 * <p>上界取 {@link SessionInfoLoader} 的 seatCount(与 {@code GET /seat-map} 同一来源),
 * 保证「客户端看得到的座位」与「服务端允许锁的座位」完全一致。
 */
@Component
@RequiredArgsConstructor
public class SeatRangeGate {

    private final SessionInfoLoader sessionInfoLoader;

    /**
     * 校验座位索引落在此场次的真实座位范围内 —— 防越界 SETBIT 撑爆 Redis 位图。
     *
     * @throws BizException code=40001 座位索引越界 / 场次座位数异常
     */
    public void requireInRange(Long sessionId, List<Integer> seats) {
        int seatCount = sessionInfoLoader.load(sessionId).getSeatCount();
        if (seatCount <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(),
                    "场次座位数异常, 无法锁座(sessionId=" + sessionId + ", seatCount=" + seatCount + ")", null);
        }
        for (Integer idx : seats) {
            if (idx == null || idx < 0 || idx >= seatCount) {
                throw new BizException(ResultCode.BAD_REQUEST.getCode(),
                        "座位索引越界: " + idx + ", 本场次有效范围 [0, " + (seatCount - 1) + "]", null);
            }
        }
    }
}
