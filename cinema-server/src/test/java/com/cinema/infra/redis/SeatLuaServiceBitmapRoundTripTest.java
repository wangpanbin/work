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
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            redisTemplate.delete(releaseMarkerKeys());
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
        List<Integer> refunded = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats, "ORD-RT-1"));
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
        List<Integer> refunded = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats, "ORD-RT-2"));
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
        assertThat(seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, List.of(10), "ORD-RT-3")))
                .containsExactly(10);

        // 第二次退票: 没有任何位需要清
        assertThat(seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, List.of(10), "ORD-RT-3"))).isEmpty();
        assertThat(soldBit(10)).isEqualTo(0);
    }

    @Test
    @DisplayName("防超卖: 本单座位已释放过, 二次释放不清掉他人已付款的座位")
    void refundSold_secondCallDoesNotClearSeatBoughtBySomeoneElse() {
        List<Integer> seats = List.of(10);

        // 1) 本单付了款: 座位 sold=1
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, seats);
        assertThat(soldBit(10)).isEqualTo(1);

        // 2) 退票主流程释放座位(写下按订单幂等标记), 但订单卡在 REFUNDING
        List<Integer> released = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats, "ORD-P0-1"));
        assertThat(released).containsExactly(10);
        assertThat(soldBit(10)).isEqualTo(0);

        // 3) 座位回到可选池后被**他人**买走: sold=1, 那是别人的票
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, seats);
        assertThat(soldBit(10)).isEqualTo(1);

        // 4) 补偿任务二次释放本单座位 → 幂等标记挡住, 一个位都不碰
        List<Integer> second = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats, "ORD-P0-1"));
        assertThat(second).isEmpty();
        assertThat(soldBit(10)).isEqualTo(1);

        // 5) 他人的座位仍然不可售 —— 再锁座必须冲突, 不存在「同一座位两张已付票」
        LuaLockResult conflict = seatLuaService.lock(SESSION_ID, seats);
        assertThat(conflict.ok()).isFalse();
        assertThat(conflict.conflict()).containsExactly(10);
    }

    @Test
    @DisplayName("防超卖: 退票主流程未释放过就中断, 补偿任务释放的是本单自己的座位")
    void refundSold_compensationReleasesOwnSeatWhenMainFlowNeverReleased() {
        List<Integer> seats = List.of(10);
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, seats);

        // 主流程在释放之前就断了 → 没有幂等标记, 补偿任务照常释放本单座位
        List<Integer> released = seatLuaService.refundSold(new SeatWrite.Paid(SESSION_ID, seats, "ORD-P0-2"));
        assertThat(released).containsExactly(10);
        assertThat(soldBit(10)).isEqualTo(0);
        assertThat(seatLuaService.lock(SESSION_ID, seats).ok()).isTrue();
    }

    @Test
    @DisplayName("原子替换位图: 旧位一个不剩, 新位全部就位, 临时 key 不留残留")
    void replaceSeats_replacesWholeBitmap() {
        // 先造一个与新向量完全不同的旧位图
        assertThat(seatLuaService.lock(SESSION_ID, List.of(10, 11)).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, List.of(10));
        assertThat(lockBit(11)).isEqualTo(1);
        assertThat(soldBit(10)).isEqualTo(1);

        // 原子替换: 新向量 = lock {20,21} / sold {30}
        RecoverResult result = seatLuaService.replaceSeats(SESSION_ID, List.of(20, 21), List.of(30));
        assertThat(result.lock()).isEqualTo(2);
        assertThat(result.sold()).isEqualTo(1);

        // 新向量就位
        assertThat(lockBit(20)).isEqualTo(1);
        assertThat(lockBit(21)).isEqualTo(1);
        assertThat(soldBit(30)).isEqualTo(1);
        // 旧位一个都不剩 —— 这正是「先删后建」原本要达到的效果, 现在没有空窗期
        assertThat(lockBit(10)).isEqualTo(0);
        assertThat(lockBit(11)).isEqualTo(0);
        assertThat(soldBit(10)).isEqualTo(0);

        // 临时 key 随换名消失, 不留残留
        assertThat(redisTemplate.hasKey(RedisKeys.sessionLockRebuild(SESSION_ID))).isFalse();
        assertThat(redisTemplate.hasKey(RedisKeys.sessionSoldRebuild(SESSION_ID))).isFalse();
    }

    @Test
    @DisplayName("原子替换位图: 脚本失败时只清临时 key, 正式位图一个字节都不碰")
    void replaceSeats_scriptFails_keepsRealBitmapKeys() {
        // 真实 Redis 上先造出正式位图: 座位 10 已售
        assertThat(seatLuaService.lock(SESSION_ID, List.of(10)).ok()).isTrue();
        seatLuaService.confirmPaid(SESSION_ID, List.of(10));

        // 把脚本执行打掉 —— 真实场景是 Redis 抖动 / OOM / 连接中断
        StringRedisTemplate failing = mock(StringRedisTemplate.class);
        when(failing.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("模拟 Redis 不可用"));
        SeatLuaService failingService = new SeatLuaService(failing, new ObjectMapper());

        assertThatThrownBy(() -> failingService.replaceSeats(SESSION_ID, List.of(), List.of(30)))
                .isInstanceOf(RuntimeException.class);

        // 关键: 只删了**临时** key —— 正式位图一个 key 都没被删
        ArgumentCaptor<Collection<String>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(failing).delete(deleted.capture());
        assertThat(deleted.getValue()).containsExactlyInAnyOrder(
                RedisKeys.sessionLockRebuild(SESSION_ID), RedisKeys.sessionSoldRebuild(SESSION_ID));
        verify(failing, never()).delete(RedisKeys.sessionLock(SESSION_ID));
        verify(failing, never()).delete(RedisKeys.sessionSold(SESSION_ID));

        // 真实 Redis 上的正式位图也没被动过 —— 座位 10 仍然是已售, 不会被误放成可选
        assertThat(soldBit(10)).isEqualTo(1);
        assertThat(lockBit(10)).isEqualTo(0);
    }

    // ------------------------------------------------------------------ helpers

    private void clearBitmaps() {
        redisTemplate.delete(List.of(RedisKeys.sessionLock(SESSION_ID), RedisKeys.sessionSold(SESSION_ID)));
        redisTemplate.delete(releaseMarkerKeys());
    }

    /**
     * 本测试用到的退票释放幂等标记 key —— 跨用例清理, 免得上一个用例留下的标记
     * 把下一个用例的释放挡掉(那正好是这个 key 的作用, 但在这里只是噪声)。
     */
    private static List<String> releaseMarkerKeys() {
        return List.of("ORD-RT-1", "ORD-RT-2", "ORD-RT-3", "ORD-P0-1", "ORD-P0-2").stream()
                .map(RedisKeys::orderSeatReleased)
                .toList();
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
