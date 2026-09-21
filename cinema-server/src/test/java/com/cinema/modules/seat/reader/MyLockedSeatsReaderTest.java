package com.cinema.modules.seat.reader;

import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MyLockedSeatsReader 单测 — 锁 Redis Hash field 解析契约.
 * <p>B-02 修复后契约:
 * Redis hash field 必须是数字字符串; 若混入非数字(field=运维误写 / schema 变更),
 * 不抛 NumberFormatException, 单条 warn + 跳过, 整条 seatMap 不爆 50000.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyLockedSeatsReaderTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;

    private MyLockedSeatsReader reader;

    @BeforeEach
    void setUp() {
        reader = new MyLockedSeatsReader(redisTemplate, orderMapper, orderItemMapper);
    }

    /**
     * B-02 回归: Redis hash 含 "abc" 这种非数字 field 时, 之前的 Integer::parseInt
     * 直接抛 NumberFormatException → 整个 /api/sessions/{id}/seat-map 返回 50000.
     * 修复后必须优雅跳过非数字 field, 正常返回剩下的合法 seatIndex.
     */
    @Test
    @DisplayName("B-02 回归: Redis hash 含非数字 field → 跳过非法项,不抛 NFE")
    void load_skipsNonNumericRedisFieldsGracefully() {
        when(redisTemplate.hasKey(anyKey())).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        // 模拟 Redis 中 hash 含正常字段 + 混入运维误写的非数字字段
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("123", "order-A");
        entries.put("abc", "order-X");        // 非法: 非数字
        entries.put("456", "order-B");
        entries.put("foo:1", "order-Y");      // 非法: schema 变更
        entries.put("", "order-Z");           // 非法: 空字符串
        when(hashOps.entries(anyKey())).thenReturn(entries);

        List<Integer> out = reader.load(100L, 1L);

        assertThat(out).containsExactly(123, 456);
    }

    @Test
    @DisplayName("load: 所有 field 都是合法数字 → 正常返回 sorted")
    void load_normalNumericFields() {
        when(redisTemplate.hasKey(anyKey())).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("11", "o1");
        entries.put("5", "o2");
        entries.put("99", "o3");
        when(hashOps.entries(anyKey())).thenReturn(entries);

        List<Integer> out = reader.load(100L, 1L);

        assertThat(out).containsExactly(5, 11, 99);
    }

    @Test
    @DisplayName("load: 所有 field 都非法 → 优雅返回空 list,不抛 NFE")
    void load_returnsEmptyWhenAllFieldsNonNumeric() {
        when(redisTemplate.hasKey(anyKey())).thenReturn(true);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<Object, Object> entries = new HashMap<>();
        entries.put("abc", "x");
        entries.put("foo", "y");
        when(hashOps.entries(anyKey())).thenReturn(entries);

        List<Integer> out = reader.load(100L, 1L);

        assertThat(out).isEmpty();
    }

    private static String anyKey() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}