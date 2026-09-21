package com.cinema.modules.seat.reader;

import com.cinema.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SessionBitmapReader 单测 — 锁定"seatCount<=0 时不可越过 Redis setBit(-1)".
 * <p>B-03 修复后契约:
 * seatCount<=0 直接抛 BizException, 绝不让 setBit 拿 -1 / Integer.MIN_VALUE 这种非法
 * offset 给 Redis(Redis 会报 ERR bit offset is not an integer or out of range,
 * Lettuce 转 RedisCommandExecutionException 把整个 seatMap 拖到 50000).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionBitmapReaderTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private SessionBitmapReader reader;

    @BeforeEach
    void setUp() {
        reader = new SessionBitmapReader(redisTemplate);
    }

    /**
     * B-03 回归: seatCount=0 之前会执行 setBit(-1, false) → Redis 报 ERR
     * bit offset is not an integer or out of range. 修复后必须先抛 BizException,
     * 绝不让 setBit 拿到 -1 这种非法 offset.
     */
    @Test
    @DisplayName("B-03 回归: seatCount=0 → 直接抛 BizException, 不可调 Redis setBit")
    void readWithPadding_zeroSeatCount_throws() {
        assertThatThrownBy(() -> reader.readWithPadding("cinema:session:lock:1", 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("座位数");
        // 重要: 不可越过 setBit, 否则 -1 offset 会让 Redis 报错
        verify(redisTemplate, never()).opsForValue();
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
    }

    @Test
    @DisplayName("B-03 回归: seatCount=-1 同样抛 BizException")
    void readWithPadding_negativeSeatCount_throws() {
        assertThatThrownBy(() -> reader.readWithPadding("cinema:session:lock:1", -1))
                .isInstanceOf(BizException.class);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("正常路径: seatCount>0 + Redis 返回 null bytes → setBit(seatCount-1, false) + 返回 padded")
    void readWithPadding_normalPath_padsToExpected() {
        when(redisTemplate.execute(any(RedisCallback.class))).thenReturn(null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // seatCount=8 → expected=1 byte; Redis 返 null → 走 padding 分支
        byte[] out = reader.readWithPadding("cinema:session:lock:1", 8);

        assertThat(out).hasSize(1);
        verify(valueOps).setBit("cinema:session:lock:1", 7L, false);
    }
}