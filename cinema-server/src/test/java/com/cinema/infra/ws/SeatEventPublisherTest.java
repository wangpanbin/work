package com.cinema.infra.ws;

import com.cinema.infra.redis.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeatEventPublisher 单测 — 事件序号注入.
 *
 * <p>本类此前没有测试, 是一致性改动里唯一没有既有断言兜底的位置。
 *
 * <p>锁住的核心不变量: <b>seq=0 表示「拿不到序号」, 客户端必须当作「无序号、总是应用」</b>。
 * 如果前端把 0 当成普通序号参与比较, {@code 0 > lastSeq[s]} 恒为假, 会把所有事件静默丢弃 ——
 * 那是一个不报错的实时系统失效, 所以这条必须在两端都有断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatEventPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SeatEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SeatEventPublisher(redisTemplate, new ObjectMapper());
    }

    /** 捕获真正发到频道上的载荷字符串 */
    private String capturePayload() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq(RedisKeys.SEAT_EVENT_CHANNEL),
                payload.capture());
        return payload.getValue();
    }

    @Test
    @DisplayName("载荷含 type / sessionId / seats / seq")
    void payloadCarriesSeq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(RedisKeys.SEAT_EVENT_SEQ)).thenReturn(42L);

        publisher.publishReleased(123L, List.of(10, 11));

        String json = capturePayload();
        assertThat(json).contains("\"type\":\"RELEASED\"")
                .contains("\"sessionId\":123")
                .contains("\"seats\":[10,11]")
                .contains("\"seq\":42");
    }

    @Test
    @DisplayName("自增打在全局计数器 key 上(不按场次分片)")
    void incrementTargetsGlobalCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(RedisKeys.SEAT_EVENT_SEQ)).thenReturn(7L);

        publisher.publishLocked(555L, List.of(3));

        verify(valueOps).increment(RedisKeys.SEAT_EVENT_SEQ);
        assertThat(RedisKeys.SEAT_EVENT_SEQ).isEqualTo("cinema:seat:event:seq");
    }

    @Test
    @DisplayName("三个发布方法都注入序号")
    void allPublishMethodsCarrySeq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(RedisKeys.SEAT_EVENT_SEQ)).thenReturn(1L, 2L, 3L);

        publisher.publishLocked(1L, List.of(1));
        assertThat(capturePayload()).contains("\"seq\":1");

        // 累计计数会跨调用保留, 捕获前先清空, 否则 capturePayload 里的 verify(times=1) 会因
        // 上一次发布而报 TooManyActualInvocations
        org.mockito.Mockito.clearInvocations(redisTemplate);

        publisher.publishSold(1L, List.of(1));
        assertThat(capturePayload()).contains("\"seq\":2");

        org.mockito.Mockito.clearInvocations(redisTemplate);

        publisher.publishReleased(1L, List.of(1));
        assertThat(capturePayload()).contains("\"seq\":3");
    }

    @Test
    @DisplayName("自增返回 null: seq 落 0, 发布仍成功不外抛")
    void nullSeqFallsBackToZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(RedisKeys.SEAT_EVENT_SEQ)).thenReturn(null);

        assertThatCode(() -> publisher.publishSold(9L, List.of(4))).doesNotThrowAnyException();

        // Map.of 不接受 null value —— 不兜底这里会直接 NPE, 事件静默丢失
        assertThat(capturePayload()).contains("\"seq\":0");
    }

    @Test
    @DisplayName("自增抛异常: seq 落 0, 事件照常发布, 不外抛")
    void seqFailureDegradesGracefully() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(RedisKeys.SEAT_EVENT_SEQ))
                .thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> publisher.publishReleased(9L, List.of(4))).doesNotThrowAnyException();

        // 关键: Redis 挂了也必须把事件发出去, 只是没有序号。
        // 宁可让客户端"总是应用"(退回纯增量), 也不能让座位状态变更丢失。
        assertThat(capturePayload()).contains("\"seq\":0");
    }

    @Test
    @DisplayName("opsForValue 抛异常: seq 落 0, 事件照常发布")
    void opsForValueFailureDegradesGracefully() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("no connection"));

        assertThatCode(() -> publisher.publishLocked(9L, List.of(4))).doesNotThrowAnyException();

        assertThat(capturePayload()).contains("\"seq\":0");
    }

    @Test
    @DisplayName("序列化抛异常: 只记日志, 不外抛, 事件不发出(保持既有语义)")
    void serializationFailureDoesNotThrow() {
        SeatEventPublisher broken = new SeatEventPublisher(redisTemplate, brokenMapper());

        assertThatCode(() -> broken.publishSold(9L, List.of(4))).doesNotThrowAnyException();

        // 注意: nextSeq() 是 Map.of(...) 的实参, 先于 writeValueAsString 求值,
        // 所以 opsForValue() 一定被调过 —— 断言必须是"事件没发出去", 而不是"零交互"。
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    private ObjectMapper brokenMapper() {
        ObjectMapper m = org.mockito.Mockito.mock(ObjectMapper.class);
        try {
            when(m.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    @Test
    @DisplayName("空座位列表也能发布(不因 seq 逻辑改变既有行为)")
    void emptySeatsStillPublishes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5L);

        publisher.publishReleased(9L, List.of());

        assertThat(capturePayload()).contains("\"seats\":[]").contains("\"seq\":5");
    }

    @Test
    @DisplayName("convertAndSend 抛异常: 不外抛(座位事件是尽力而为, 不能影响主业务)")
    void publishFailureDoesNotThrow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        doThrow(new RuntimeException("channel closed"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> publisher.publishReleased(9L, List.of(4))).doesNotThrowAnyException();
    }
}
