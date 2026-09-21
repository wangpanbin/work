package com.cinema.modules.seat.reader;

import com.cinema.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 场次位图读侧: 读取 lock/sold bitmap + 自动 padding 到 seatCount 长度.
 *
 * <p>#4 收尾: 抽自 SeatService.getBitmap. 关键 invariant —
 * 场次首次访问时 SETBIT(seatCount-1, false) 把位图长度分配到位,
 * 保证后续 GET 长度稳定(不足部分按 0 补齐).
 *
 * <p>B-03 修复: seatCount<=0 必须早抛 BizException, 绝不让 setBit 拿到 -1 /
 * Integer.MIN_VALUE 这种非法 offset(Redis 会报 ERR bit offset is not an integer
 * or out of range, Lettuce 转 RedisCommandExecutionException 把整个
 * /api/sessions/{id}/seat-map 拖到 50000). seatCount<=0 触发场景:
 *  - hall 缺失(SessionInfoLoader.hall null 时给 0 兜底)
 *  - init data 漏字段
 *  - 任何 schema 漂移
 */
@Component
@RequiredArgsConstructor
public class SessionBitmapReader {

    private final StringRedisTemplate redisTemplate;

    /**
     * 读取并 pad bitmap. 若 Redis 内 bytes 长度不足 seatCount 对应字节数,
     * 自动 SETBIT 最高位 = false 完成长度分配 + 返回 zero-padded 副本.
     *
     * @param key       Redis bitmap key (e.g. sessionLock/sold)
     * @param seatCount 位图应承载的座位数, 必须 > 0
     * @return 长度 = ceil(seatCount/8) 的 byte[]
     * @throws BizException 当 seatCount <= 0
     */
    public byte[] readWithPadding(String key, int seatCount) {
        if (seatCount <= 0) {
            throw new BizException("场次座位数异常,请联系管理员(key=" + key + ", seatCount=" + seatCount + ")");
        }
        byte[] bytes = redisTemplate.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        int expected = (seatCount + 7) / 8;
        if (bytes == null || bytes.length < expected) {
            redisTemplate.opsForValue().setBit(key, seatCount - 1L, false);
            byte[] padded = new byte[expected];
            if (bytes != null) {
                System.arraycopy(bytes, 0, padded, 0, bytes.length);
            }
            return padded;
        }
        return bytes;
    }
}