package com.cinema.modules.seat;

import com.cinema.modules.seat.vo.SeatMapVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeatAvailability} 单元测试。
 *
 * <p><b>本模块是纯搬迁</b>, 所以这组用例的作用是**钉住搬迁前的判定口径**:
 * 每条断言都能对应回搬迁前某个方法里的某一行判断。口径若被「顺手修正」,
 * 这里就会红 —— 那正是我们要拦住的: 模型看到的可选数与服务端实际锁到的座位必须一致。
 *
 * <p>网格统一用 8 行 x 12 列 = 96 座(与 {@code OrderProposalServiceTest} 同一套),
 * 另有一条 3x4 的小网格专门用来验证「最长连座跨行不切断」。
 */
class SeatAvailabilityTest {

    private static final int ROWS = 8;
    private static final int COLS = 12;
    private static final int SEAT_COUNT = 96;

    // =================================================================
    // 可选 / 被锁 / 已售
    // =================================================================

    @Test
    @DisplayName("isAvailable: lock 位与 sold 位都为 0 才算可选")
    void isAvailable_onlyWhenNeitherLockedNorSold() {
        boolean[] locked = new boolean[SEAT_COUNT];
        boolean[] sold = new boolean[SEAT_COUNT];
        locked[3] = true;
        sold[7] = true;

        SeatAvailability av = availability(seatMap(locked, sold, SEAT_COUNT));

        assertThat(av.isAvailable(0)).isTrue();
        assertThat(av.isAvailable(3)).isFalse();   // 被别人锁着
        assertThat(av.isAvailable(7)).isFalse();   // 已售
    }

    @Test
    @DisplayName("isAvailable: 越界索引(负数 / >= seatCount)返回 false")
    void isAvailable_outOfRange_isFalse() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.isAvailable(-1)).isFalse();
        assertThat(av.isAvailable(SEAT_COUNT)).isFalse();
        assertThat(av.isAvailable(SEAT_COUNT + 1000)).isFalse();
    }

    @Test
    @DisplayName("计数: sold 优先 —— 同时 lock+sell 的座位只算已售, 不算被锁")
    void counts_soldTakesPrecedenceOverLock() {
        boolean[] locked = new boolean[SEAT_COUNT];
        boolean[] sold = new boolean[SEAT_COUNT];
        locked[5] = true;
        sold[5] = true;      // 同时锁 + 售
        locked[7] = true;    // 只锁
        sold[9] = true;      // 只售

        SeatAvailability av = availability(seatMap(locked, sold, SEAT_COUNT));

        assertThat(av.soldCount()).isEqualTo(2);
        assertThat(av.lockedCount()).isEqualTo(1);
        assertThat(av.availableCount()).isEqualTo(SEAT_COUNT - 3);
        assertThat(av.isAvailable(5)).isFalse();
        assertThat(av.isAvailable(7)).isFalse();
        assertThat(av.isAvailable(9)).isFalse();
    }

    // =================================================================
    // 最长连座
    // =================================================================

    @Test
    @DisplayName("longestAvailableRun: 全场可选时从 0 开始, 长度 = seatCount")
    void longestRun_allAvailable() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        SeatAvailability.Run run = av.longestAvailableRun();

        assertThat(run.found()).isTrue();
        assertThat(run.start()).isZero();
        assertThat(run.length()).isEqualTo(SEAT_COUNT);
    }

    @Test
    @DisplayName("longestAvailableRun: 跨行不切断 —— 3x4 网格锁掉 0 号, 最长段是 1..11(11 座)")
    void longestRun_doesNotBreakOnRowBoundary() {
        int small = 12;
        boolean[] locked = new boolean[small];
        locked[0] = true;

        SeatAvailability av = availability(seatMap(locked, new boolean[small], small, 3, 4));

        SeatAvailability.Run run = av.longestAvailableRun();

        // 若按行切断, 最长只会是 3 座(1,2,3); 搬迁前不切断 → 11 座
        assertThat(run.start()).isEqualTo(1);
        assertThat(run.length()).isEqualTo(11);
    }

    @Test
    @DisplayName("longestAvailableRun: 同长时取先出现的那一段")
    void longestRun_tiesGoToFirstSegment() {
        int small = 12;
        boolean[] sold = new boolean[small];
        sold[0] = true;
        sold[6] = true;
        // 1..5(5 座) 与 7..11(5 座) 等长 → 先出现的那段胜出
        SeatAvailability av = availability(seatMap(new boolean[small], sold, small, 3, 4));

        SeatAvailability.Run run = av.longestAvailableRun();

        assertThat(run.start()).isEqualTo(1);
        assertThat(run.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("longestAvailableRun: 全场不可选时没有可选段")
    void longestRun_noneAvailable() {
        boolean[] allLocked = new boolean[SEAT_COUNT];
        java.util.Arrays.fill(allLocked, true);

        SeatAvailability av = availability(seatMap(allLocked, new boolean[SEAT_COUNT], SEAT_COUNT));

        SeatAvailability.Run run = av.longestAvailableRun();

        assertThat(run.found()).isFalse();
        assertThat(run.length()).isZero();
    }

    // =================================================================
    // findRun —— FIRST_ROW
    // =================================================================

    @Test
    @DisplayName("findRun FIRST_ROW: 全场可选时取索引最小的连座")
    void firstRow_takesLowestIndexes() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("findRun FIRST_ROW: 跳过被锁的座位, 从第一个可选处起算")
    void firstRow_skipsLockedPrefix() {
        boolean[] locked = new boolean[SEAT_COUNT];
        locked[0] = true;
        locked[1] = true;

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW)).containsExactly(2, 3);
    }

    @Test
    @DisplayName("findRun FIRST_ROW: 行内连续 —— 跨行相邻的座位不算连座")
    void firstRow_neverCrossesRowBoundary() {
        boolean[] locked = new boolean[SEAT_COUNT];
        for (int i = 0; i <= 10; i++) {
            locked[i] = true;   // 第 0 行只剩 11 号可选, 它右边没有同行的邻居
        }

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        List<Integer> run = av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW);

        // 11 号是第 0 行最后一列, 组不成 2 连座 → 落到第 1 行的开头
        assertThat(run).containsExactly(12, 13);
        assertThat(run.get(0) / COLS).isEqualTo(run.get(1) / COLS);
    }

    @Test
    @DisplayName("findRun FIRST_ROW + preferRow: 只在该行里找")
    void firstRow_withPreferRow() {
        boolean[] locked = new boolean[SEAT_COUNT];
        for (int i = 0; i < COLS; i++) {
            locked[i] = true;   // 第 0 行整行锁掉
        }

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        // 不指定行 → 落到第 1 行
        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW)).containsExactly(12, 13);
        // 指定第 0 行 → 整行被锁, 没有连座
        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW, 0)).isEmpty();
        // 指定第 1 行 → 命中
        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW, 1)).containsExactly(12, 13);
    }

    @Test
    @DisplayName("findRun FIRST_ROW: 找不到够长的连座时返回空列表")
    void firstRow_notEnoughRoom() {
        boolean[] locked = new boolean[SEAT_COUNT];
        // 全部锁掉, 只留 0 号一个可选
        java.util.Arrays.fill(locked, true);
        locked[0] = false;

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(2, SeatAvailability.RunPolicy.FIRST_ROW)).isEmpty();
        assertThat(av.findRun(1, SeatAvailability.RunPolicy.FIRST_ROW)).containsExactly(0);
    }

    // =================================================================
    // findRun —— CENTERED
    // =================================================================

    @Test
    @DisplayName("findRun CENTERED: 8x12 全场可选时取正中间那组(第 4 行 5/6 座)")
    void centered_picksMiddleOfGrid() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(2, SeatAvailability.RunPolicy.CENTERED)).containsExactly(40, 41);
    }

    @Test
    @DisplayName("findRun CENTERED: 中心行还有位置时仍留在中心行(行距权重高于列距)")
    void centered_staysInCenterRowWhenPossible() {
        boolean[] locked = new boolean[SEAT_COUNT];
        locked[40] = true;
        locked[41] = true;

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        List<Integer> run = av.findRun(2, SeatAvailability.RunPolicy.CENTERED);

        assertThat(run.get(0) / COLS).isEqualTo(3);
        assertThat(run.get(1) - run.get(0)).isEqualTo(1);
        assertThat(run).doesNotContain(40, 41);
    }

    @Test
    @DisplayName("findRun CENTERED: 中心行整行被占 → 退到相邻行(同分取靠前的那一行)")
    void centered_fallsBackToAdjacentRowWhenCenterRowFull() {
        boolean[] locked = new boolean[SEAT_COUNT];
        for (int i = 3 * COLS; i < 4 * COLS; i++) {
            locked[i] = true;   // 第 3 行(0-based)整行锁掉
        }

        SeatAvailability av = availability(seatMap(locked, new boolean[SEAT_COUNT], SEAT_COUNT));

        List<Integer> run = av.findRun(2, SeatAvailability.RunPolicy.CENTERED);

        // 行距权重 = 行差 x cols → 第 2 行与第 4 行同分, 先遇到的第 2 行胜出
        assertThat(run.get(0) / COLS).isEqualTo(2);
        assertThat(run).containsExactly(28, 29);
    }

    @Test
    @DisplayName("findRun CENTERED: 全场不可选时返回空列表")
    void centered_noneAvailable() {
        boolean[] allLocked = new boolean[SEAT_COUNT];
        java.util.Arrays.fill(allLocked, true);

        SeatAvailability av = availability(seatMap(allLocked, new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(2, SeatAvailability.RunPolicy.CENTERED)).isEmpty();
    }

    @Test
    @DisplayName("findRun: count < 1 时返回空列表")
    void findRun_invalidCount() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.findRun(0, SeatAvailability.RunPolicy.FIRST_ROW)).isEmpty();
        assertThat(av.findRun(0, SeatAvailability.RunPolicy.CENTERED)).isEmpty();
    }

    // =================================================================
    // 网格映射
    // =================================================================

    @Test
    @DisplayName("rowOf / colOf: row = idx / cols, col = idx % cols")
    void rowAndColMapping() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.rowOf(41)).isEqualTo(3);
        assertThat(av.colOf(41)).isEqualTo(5);
        assertThat(av.rowOf(0)).isZero();
        assertThat(av.colOf(0)).isZero();
    }

    @Test
    @DisplayName("isSameRowContiguous: 同行且索引连续才算连座")
    void sameRowContiguous() {
        SeatAvailability av = availability(seatMap(new boolean[SEAT_COUNT], new boolean[SEAT_COUNT], SEAT_COUNT));

        assertThat(av.isSameRowContiguous(List.of(40, 41, 42))).isTrue();
        assertThat(av.isSameRowContiguous(List.of(40, 41))).isTrue();
        // 40 与 42 之间隔着 41 → 索引不连续
        assertThat(av.isSameRowContiguous(List.of(40, 42))).isFalse();
        // 11 与 12 相邻但跨行
        assertThat(av.isSameRowContiguous(List.of(11, 12))).isFalse();
        // 单个座位平凡成立
        assertThat(av.isSameRowContiguous(List.of(7))).isTrue();
    }

    // =================================================================
    // helpers
    // =================================================================

    private static SeatAvailability availability(SeatMapVO vo) {
        return SeatAvailability.of(vo);
    }

    private static SeatMapVO seatMap(boolean[] locked, boolean[] sold, int seatCount) {
        return seatMap(locked, sold, seatCount, ROWS, COLS);
    }

    private static SeatMapVO seatMap(boolean[] locked, boolean[] sold, int seatCount, int rows, int cols) {
        return SeatMapVO.builder()
                .sessionId(1001L)
                .rows(rows)
                .cols(cols)
                .seatCount(seatCount)
                .lockBitmap(encode(locked))
                .soldBitmap(encode(sold))
                .build();
    }

    /** 把 boolean[] 编成 Base64 位图(字节内高位在前, 与 utils/bitmap.ts 前端解码一致) */
    private static String encode(boolean[] bits) {
        byte[] bytes = new byte[Math.max(1, (bits.length + 7) / 8)];
        for (int i = 0; i < bits.length; i++) {
            if (!bits[i]) {
                continue;
            }
            bytes[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
