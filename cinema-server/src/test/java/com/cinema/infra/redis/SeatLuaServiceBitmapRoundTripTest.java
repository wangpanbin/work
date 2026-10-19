package com.cinema.infra.redis;

import com.cinema.modules.seat.reader.SessionBitmapReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 座位位图写入链路的<strong>真实 Redis 往返测试</strong>。
 *
 * <p><b>为什么必须是真实 Redis</b>: 三个座位 Lua 脚本的语义差异只体现在<strong>位级别的副作用</strong>上 ——
 * {@code release_seat.lua} 只清 lock 位, {@code refund_seat.lua} 同清 sold + lock。
 * 用 mock 只能验到「调了哪个方法」, 验不到「位有没有真的被清掉」。
 * E2E 2026-09-29 的 P1-1(退票误走释放路径 → 座位 sold 位残留 → 永久不可再售)
 * 正是这类只有真实位图才能暴露的缺陷, 所以这里必须用真 Redis。
 *
 * <p><b>Redis 不可达时跳过而不是失败</b>: 本地/CI 可能没有 Redis,
 * 不该因为环境缺件把构建打红。跳过会记在报告里, 不会伪装成「通过」。
 *
 * <p>本测试只读写 {@code cinema:session:9900001:*} 两个 key, 不碰任何演示场次的数据。
 */
class SeatLuaServiceBitmapRoundTripTest {

    /** 专用场次 id —— 与演示数据(1~2000 段)不重叠, 避免污染真实位图。 */
    private static final Long SESSION_ID = 9_900_001L;

    /** 座位数取 2 号厅的 140, 顺带覆盖 readWithPadding 的补齐逻辑。 */
    private static final int SEAT_COUNT = 140;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SeatLuaService seatLuaService;
    private SessionBitmapReader bitmapReader;

    @BeforeEach
    void setUp() {
        connectionFactory = newRedisFactory();
        Assumptions.assumeTrue(connectionFactory != null, "Redis 不可达, 跳过座位位图往返测试");
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        seatLuaService = new SeatLuaService(redisTemplate, new ObjectMapper());
        seatLuaService.preloadScripts();
        bitmapReader = new SessionBitmapReader(redisTemplate);
        clearBitmaps();
    }

    @AfterEach
    void tearDown() {
        if (redisTemplate != null) {
            redisTemplate.delete(List.of(RedisKeys.sessionLock(SESSION_ID), RedisKeys.sessionSold(SESSION_ID)));
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("位图往返: 锁座 → 支付确认 → 退票 → 再锁座, 座位真的回到可选池")
    void roundTrip_backToAvailable() {
        List<Integer> seats = List.of(10, 11);

        // 1) 锁座: 置 lock 位, sold 位不动
        LuaLockResult lockResult = seatLuaService.lock(SESSION_ID, seats);
        assertThat(lockResult.ok()).isTrue();
        assertThat(lockResult.conflict()).isEmpty();
        assertThat(lockBit(10)).isEqualTo(1);
        assertThat(lockBit(11)).isEqualTo(1);
        assertThat(soldBit(10)).isEqualTo(0);
        assertThat(soldBit(11)).isEqualTo(0);

        // 2) 支付确认: 置 sold 并清 lock
        List<Integer> confirmed = seatLuaService.confirmPaid(SESSION_ID, seats);
        assertThat(confirmed).containsExactlyInAnyOrder(10, 11);
        assertThat(lockBit(10)).isEqualTo(0);
        assertThat(lockBit(11)).isEqualTo(0);
        assertThat(soldBit(10)).isEqualTo(1);
        assertThat(soldBit(11)).isEqualTo(1);

        // 3) 退票: 同清 sold + lock
        List<Integer> refunded = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats));
        assertThat(refunded).containsExactlyInAnyOrder(10, 11);
        assertThat(soldBit(10)).isEqualTo(0);
        assertThat(soldBit(11)).isEqualTo(0);
        assertThat(lockBit(10)).isEqualTo(0);
        assertThat(lockBit(11)).isEqualTo(0);

        // 4) 再锁座必须成功 —— 座位真的回到了可选池
        LuaLockResult again = seatLuaService.lock(SESSION_ID, seats);
        assertThat(again.ok()).isTrue();
        assertThat(again.conflict()).isEmpty();
    }

    @Test
    @DisplayName("P1-1 陷阱: 已售座位走释放路径时 sold 位不变, 座位仍然不可售")
    void releaseUnpaid_doesNotClearSoldBit() {
        List<Integer> seats = List.of(10, 11);
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, seats);
        assertThat(soldBit(10)).isEqualTo(1);

        // 退票若误用「只清 lock 位」的释放路径: sold 位一个都不动, 返回空列表
        List<Integer> released = seatLuaService.releaseUnpaid(new SeatWrite.Unpaid(SESSION_ID, seats));
        assertThat(released).isEmpty();
        assertThat(soldBit(10)).isEqualTo(1);
        assertThat(soldBit(11)).isEqualTo(1);

        // 座位仍然不可售 —— 再锁座必须冲突
        LuaLockResult conflict = seatLuaService.lock(SESSION_ID, seats);
        assertThat(conflict.ok()).isFalse();
        assertThat(conflict.conflict()).containsExactlyInAnyOrder(10, 11);

        // 换成退票路径才真正清零
        List<Integer> refunded = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats));
        assertThat(refunded).containsExactlyInAnyOrder(10, 11);
        assertThat(soldBit(10)).isEqualTo(0);
        assertThat(soldBit(11)).isEqualTo(0);
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
    }

    @Test
    @DisplayName("释放未支付座位: 只清 lock 位, 同批里已售的座位不受影响")
    void releaseUnpaid_clearsLockOnly() {
        assertThat(seatLuaService.lock(SESSION_ID, List.of(10, 11)).ok()).isTrue();
        // 先把 11 支付掉, 于是 10 是 lock 态、11 是 sold 态
        seatLuaService.confirmPaid(SESSION_ID, List.of(11));
        assertThat(lockBit(10)).isEqualTo(1);
        assertThat(soldBit(11)).isEqualTo(1);

        List<Integer> released = seatLuaService.releaseUnpaid(
                new SeatWrite.Unpaid(SESSION_ID, List.of(10, 11)));

        // 只释放了未售出的 10
        assertThat(released).containsExactly(10);
        assertThat(lockBit(10)).isEqualTo(0);
        assertThat(soldBit(11)).isEqualTo(1);
    }

    @Test
    @DisplayName("锁座冲突是整批原子失败: 冲突批次里不冲突的座位也不置位")
    void lock_conflict_failsWholeBatch() {
        assertThat(seatLuaService.lock(SESSION_ID, List.of(10)).ok()).isTrue();

        LuaLockResult result = seatLuaService.lock(SESSION_ID, List.of(11, 10));

        assertThat(result.ok()).isFalse();
        assertThat(result.conflict()).containsExactly(10);
        // 11 与 10 同批, 但整批失败 → 11 也不能被锁走
        assertThat(lockBit(11)).isEqualTo(0);
    }

    @Test
    @DisplayName("退票幂等: 座位已是可选时再退一次, 返回空列表且不误伤他人")
    void refundSold_isIdempotent() {
        assertThat(seatLuaService.lock(SESSION_ID, List.of(10)).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, List.of(10));
        assertThat(seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, List.of(10))))
                .containsExactly(10);

        // 第二次退票: 没有任何位需要清
        assertThat(seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, List.of(10)))).isEmpty();
        assertThat(soldBit(10)).isEqualTo(0);
    }

    // ------------------------------------------------------------------ helpers

    private void clearBitmaps() {
        redisTemplate.delete(List.of(RedisKeys.sessionLock(SESSION_ID), RedisKeys.sessionSold(SESSION_ID)));
    }

    /**
     * 建一个连本机 Redis 的连接工厂; 连不上返回 null(由调用方转成「跳过」)。
     */
    private static LettuceConnectionFactory newRedisFactory() {
        String host = System.getProperty("redis.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("redis.port", "6379"));
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        try (var conn = factory.getConnection()) {
            conn.ping();
        } catch (Exception e) {
            factory.destroy();
            return null;
        }
        return factory;
    }

    /**
     * 位序与生产链路一致: byte = index / 8, bit = 7 - (index % 8)(Redis 大端)。
     * 前端 {@code cinema-web/src/utils/bitmap.ts#decodeBitmap} 用的是同一套。
     */
    private static int bitOf(byte[] bytes, int index) {
        int byteIndex = index >> 3;
        if (byteIndex >= bytes.length) {
            return 0;
        }
        int value = bytes[byteIndex] & 0xFF;
        return (value >> (7 - (index & 7))) & 1;
    }

    private int lockBit(int index) {
        return bitOf(bitmapReader.readWithPadding(RedisKeys.sessionLock(SESSION_ID), SEAT_COUNT), index);
    }

    private int soldBit(int index) {
        return bitOf(bitmapReader.readWithPadding(RedisKeys.sessionSold(SESSION_ID), SEAT_COUNT), index);
    }
}
