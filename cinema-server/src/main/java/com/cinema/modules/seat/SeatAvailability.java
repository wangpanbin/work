package com.cinema.modules.seat;

import com.cinema.modules.seat.vo.SeatMapVO;

import java.util.List;

/**
 * 座位可用性视图 —— 把「双位图 → 哪些座位可选 / 有几连座」收敛到一处。
 *
 * <p><b>为什么要有这个模块</b>: 解码 lock/sold 双位图并据此选座的逻辑此前散在三处 ——
 * {@code ChatTools.getSeatSummary}、{@code ChatTools.findContiguousSeats}、
 * {@code OrderProposalService#createProposal}。三份各写一遍位序、各写一遍连座扫描,
 * 改一处漏两处就会出现「模型看到的可选数」与「服务端实际锁到的座位」对不上。
 *
 * <p><b>位序约定</b>(与生产链路完全一致): 位图是 Base64(字节内高位在前),
 * 第 i 位对应 seat_index = i;{@code byte = i / 8}, {@code bit = 7 - (i % 8)}。
 * 与前端 {@code cinema-web/src/utils/bitmap.ts#decodeBitmap} 同一套。
 *
 * <p><b>本接口不写 Redis</b>: 它是对一次 {@link SeatMapVO} 快照的只读视图。
 * 位图的实时性由调用方负责 —— 调用前必须重新读一次座位图, 快照不保证新鲜。
 *
 * <p><b>口径与历史行为严格一致</b>(本模块是纯搬迁, 不改任何判定口径):
 * <ul>
 *   <li>「可选」= lock 位为 0 <b>且</b> sold 位为 0。</li>
 *   <li>{@link #lockedCount()} 只数「sold=0 且 lock=1」的座位 —— sold 优先,
 *       与 {@code getSeatSummary} 原有的 if/else-if 分支同口径。</li>
 *   <li>{@link #longestAvailableRun()} 的连续段<strong>不按行切断</strong> ——
 *       原实现就是跨整个 seat_index 空间扫的, 该字段是模型可见输出, 改了会影响选座建议。</li>
 *   <li>{@link #findRun(int, RunPolicy)} 的 {@code FIRST_ROW} 与 {@code CENTERED}
 *       两种策略的行内边界与并列取谁, 都与原实现逐字一致(见各自的 javadoc)。</li>
 * </ul>
 */
public interface SeatAvailability {

    /**
     * 连座选取策略。
     */
    enum RunPolicy {

        /**
         * 从低索引开始扫, 撞到第一个够长的连座就返回(行内连续, 跨行不算)。
         *
         * <p>并列时取<b>索引最小</b>的那一段 —— 原 {@code ChatTools#findContiguousSeats}
         * 就是这个口径。指定 {@code preferRow} 时只在该行里找, 找不到返回空。
         */
        FIRST_ROW,

        /**
         * 取最靠近座位图中心的一组连座。
         *
         * <p>打分 = {@code |行号 - 中心行| * cols + |候选中列 - 中心列|},
         * 行距按列数加权(所以行距比列距重);同分时取<b>行号小、同行内起始列小</b>的那一组。
         * 原 {@code OrderProposalService#pickCenteredRun} 就是这个口径。
         */
        CENTERED
    }

    /**
     * 一段连续可选座位的区间。
     *
     * @param start  起始 seat_index
     * @param length 长度;{@code 0} 表示不存在可选段
     */
    record Run(int start, int length) {

        /** 是否找到了一段。 */
        public boolean found() {
            return length > 0;
        }
    }

    /**
     * 由一次座位图快照构造可用性视图。
     *
     * <p>行/列数为 0 时按 1 兜底 —— 原 {@code OrderProposalService} 就是这么兜的;
     * 原 {@code ChatTools#findContiguousSeats} 则会在 cols=0 时抛算术异常, 统一后不再崩。
     */
    static SeatAvailability of(SeatMapVO vo) {
        return BitmapSeatAvailability.of(vo);
    }

    /**
     * 某个座位当前是否可选(既没被锁也没被卖)。
     *
     * <p>越界索引(负数或 {@code >= seatCount})一律返回 {@code false}。
     */
    boolean isAvailable(int seatIndex);

    /** 可选座位总数。 */
    int availableCount();

    /**
     * 被锁但未售的座位数(「别人锁着的」)。
     *
     * <p>sold 位为 1 的座位不计入 —— 它算已售, 不算被锁。
     */
    int lockedCount();

    /** 已售座位数。 */
    int soldCount();

    /**
     * 最长的一段连续可选座位。
     *
     * <p><b>不按行切断</b>: 跨行的相邻索引也算连续(原实现口径, 模型可见字段, 不得擅改)。
     */
    Run longestAvailableRun();

    /**
     * 找 {@code count} 个连座。
     *
     * @param count  座位数, 必须 ≥ 1
     * @param policy 选取策略
     * @return 座位索引列表;找不到返回空列表
     */
    List<Integer> findRun(int count, RunPolicy policy);

    /**
     * 找 {@code count} 个连座, 并可指定偏好行。
     *
     * <p>{@code preferRow} 只对 {@link RunPolicy#FIRST_ROW} 有意义:
     * 指定后只在该行里找, 找不到返回空列表;{@code CENTERED} 策略忽略此参数。
     */
    List<Integer> findRun(int count, RunPolicy policy, Integer preferRow);

    /**
     * 一组座位是否同行且索引连续(网格映射 {@code row = idx / cols}, {@code col = idx % cols})。
     */
    boolean isSameRowContiguous(List<Integer> seatIndexes);

    /** 座位所在行号(从 0 开始)。 */
    int rowOf(int seatIndex);

    /** 座位所在列号(从 0 开始)。 */
    int colOf(int seatIndex);
}
