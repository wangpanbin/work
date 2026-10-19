package com.cinema.modules.seat;

import com.cinema.modules.seat.entity.Seat;

import java.util.List;
import java.util.Map;

/**
 * 座位描述渲染 —— 「一组座位索引 → 一句人话」的唯一实现。
 *
 * <p><b>为什么收拢</b>: 渲染逻辑此前散在三处 ——
 * 订单列表批量组装、订单详情、下单提案。三处各写一遍「X排Y座」的拼法与「、」分隔,
 * 于是同一个座位在不同屏幕上可能显示成不同的话; 其中两处的<b>降级口径</b>还不一样
 * (一处缺座位就少写一个, 一处退化成「7号座位」)。本类把「怎么渲染」与
 * 「缺了怎么办」都收进来, 差异用 {@link Fallback} 显式表达, 不再藏在调用点里。
 *
 * <p><b>权威来源</b>: 排/座号一律取 {@code seat} 表(权威), 座位索引只是引用。
 * 渲染结果按 seatIndex 升序、用「、」连接。
 *
 * <p><b>降级策略的三个取值都是既有行为</b>, 本类是纯搬迁, 输出逐字节不变:
 * <ul>
 *   <li>{@link Fallback#SKIP_POSITIONAL} —— 订单列表: 缺座位跳过, 但分隔符按<b>原始位置</b>插入
 *       (首个座位缺失时会出现前导「、」)。</li>
 *   <li>{@link Fallback#SKIP_COMPACT} —— 订单详情: 缺座位跳过, 只连接渲染出来的项。</li>
 *   <li>{@link Fallback#INDEX_ONLY} —— 下单提案: 缺座位退化成「&lt;索引&gt;号座位」,
 *       宁可给个粗描述也不能让用户少看到一个座位。</li>
 * </ul>
 * 前两者只在<b>数据漂移</b>(影厅改过座位数、订单里的旧 seat_index 已不存在)时才显出差别。
 * 统一它们属于对外可观测变更, 需另行拍板, 本轮不做。
 */
public final class SeatLabel {

    /**
     * 缺座位时的降级策略。
     */
    public enum Fallback {

        /**
         * 缺座位跳过; 分隔符按<b>原始位置</b>插入。
         *
         * <p>订单列表的既有行为: 第一个座位缺失时, 结果会带一个前导「、」。
         * 保留它是为了让该字段的输出逐字节不变。
         */
        SKIP_POSITIONAL,

        /**
         * 缺座位跳过; 只连接渲染出来的项(无前导分隔符)。
         *
         * <p>订单详情的既有行为。
         */
        SKIP_COMPACT,

        /**
         * 缺座位退化成「&lt;索引&gt;号座位」。
         *
         * <p>下单提案的既有行为 —— 提案当场就要把座位摆给用户看, 少一个会让人以为没选上。
         */
        INDEX_ONLY
    }

    private SeatLabel() {
    }

    /**
     * 渲染一组座位描述。
     *
     * @param seatIndexes  座位索引(渲染时按升序排列)
     * @param authoritative seatIndex → 权威座位行(可缺项, 缺项走降级策略)
     * @param fallback     缺座位时的降级策略
     * @return 形如「5排4座、5排5座」的描述; 没有任何可渲染内容时返回空串
     */
    public static String describe(List<Integer> seatIndexes,
                                  Map<Integer, Seat> authoritative,
                                  Fallback fallback) {
        if (seatIndexes == null || seatIndexes.isEmpty()) {
            return "";
        }
        List<Integer> sorted = seatIndexes.stream().sorted().toList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            Integer idx = sorted.get(i);
            Seat seat = authoritative == null ? null : authoritative.get(idx);
            switch (fallback) {
                case SKIP_POSITIONAL -> {
                    if (i > 0) {
                        sb.append("、");
                    }
                    if (seat != null) {
                        sb.append(format(seat));
                    }
                }
                case SKIP_COMPACT -> {
                    if (seat == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append("、");
                    }
                    sb.append(format(seat));
                }
                case INDEX_ONLY -> {
                    if (i > 0) {
                        sb.append("、");
                    }
                    sb.append(seat != null ? format(seat) : idx + "号座位");
                }
                default -> throw new IllegalStateException("未知降级策略: " + fallback);
            }
        }
        return sb.toString();
    }

    /**
     * 单个座位的权威描述 —— 「X排Y座」。
     *
     * <p>排/座号取 {@code seat} 表的 {@code row_no} / {@code col_no};
     * 缺失时退化成「&lt;索引&gt;号座位」由调用方的降级策略决定, 本方法不处理。
     */
    public static String format(Seat seat) {
        return seat.getRowNo() + "排" + seat.getColNo() + "座";
    }
}
