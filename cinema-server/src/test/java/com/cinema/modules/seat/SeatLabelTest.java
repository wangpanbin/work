package com.cinema.modules.seat;

import com.cinema.modules.seat.entity.Seat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeatLabel} 单元测试 —— 座位描述渲染的唯一实现。
 *
 * <p>本模块是纯搬迁, 所以这组用例的作用是**钉死三种既有降级口径**:
 * <ul>
 *   <li>{@code SKIP_POSITIONAL} —— 订单列表: 缺座位跳过, 分隔符按位置插(可能出前导「、」)</li>
 *   <li>{@code SKIP_COMPACT} —— 订单详情: 缺座位跳过, 只连渲染出来的项</li>
 *   <li>{@code INDEX_ONLY} —— 下单提案: 缺座位退化成「&lt;索引&gt;号座位」</li>
 * </ul>
 * 三个口径**不能合并** —— 合并会让订单列表/详情/提案的 seatDesc 发生变化,
 * 属于对外可观测变更, 需另行拍板。
 */
class SeatLabelTest {

    private static Seat seat(int index, int rowNo, int colNo) {
        Seat s = new Seat();
        s.setSeatIndex(index);
        s.setRowNo(rowNo);
        s.setColNo(colNo);
        return s;
    }

    private static Map<Integer, Seat> map(Seat... seats) {
        Map<Integer, Seat> m = new HashMap<>();
        for (Seat s : seats) {
            m.put(s.getSeatIndex(), s);
        }
        return m;
    }

    @Test
    @DisplayName("全部命中 → 按 seatIndex 升序、用「、」连接")
    void allFound_sortedAndJoined() {
        Map<Integer, Seat> seats = map(seat(52, 5, 5), seat(40, 4, 6), seat(41, 4, 5));

        String desc = SeatLabel.describe(List.of(52, 40, 41), seats, SeatLabel.Fallback.SKIP_COMPACT);

        // 排序依据是 seatIndex(40 → 41 → 52), 不是列号
        assertThat(desc).isEqualTo("4排6座、4排5座、5排5座");
    }

    @Test
    @DisplayName("SKIP_COMPACT: 缺座位跳过且不留前导分隔符")
    void skipCompact_dropsMissingWithoutLeadingSeparator() {
        Map<Integer, Seat> seats = map(seat(41, 4, 5));

        String desc = SeatLabel.describe(List.of(40, 41), seats, SeatLabel.Fallback.SKIP_COMPACT);

        assertThat(desc).isEqualTo("4排5座");
    }

    @Test
    @DisplayName("SKIP_POSITIONAL: 缺座位跳过, 但分隔符按位置插(保留前导「、」的历史行为)")
    void skipPositional_keepsLeadingSeparator() {
        Map<Integer, Seat> seats = map(seat(41, 4, 5));

        String desc = SeatLabel.describe(List.of(40, 41), seats, SeatLabel.Fallback.SKIP_POSITIONAL);

        // 订单列表的既有行为: 首个座位缺失 → 结果带一个前导「、」
        assertThat(desc).isEqualTo("、4排5座");
    }

    @Test
    @DisplayName("SKIP_POSITIONAL: 全部命中时与 SKIP_COMPACT 输出一致")
    void skipPositional_sameWhenAllFound() {
        Map<Integer, Seat> seats = map(seat(40, 4, 5), seat(41, 4, 6));

        String positional = SeatLabel.describe(List.of(40, 41), seats, SeatLabel.Fallback.SKIP_POSITIONAL);
        String compact = SeatLabel.describe(List.of(40, 41), seats, SeatLabel.Fallback.SKIP_COMPACT);

        assertThat(positional).isEqualTo("4排5座、4排6座");
        assertThat(positional).isEqualTo(compact);
    }

    @Test
    @DisplayName("INDEX_ONLY: 缺座位退化成「<索引>号座位」, 不丢座位")
    void indexOnly_fallsBackToIndexLabel() {
        Map<Integer, Seat> seats = map(seat(41, 4, 5));

        String desc = SeatLabel.describe(List.of(40, 41), seats, SeatLabel.Fallback.INDEX_ONLY);

        assertThat(desc).isEqualTo("40号座位、4排5座");
    }

    @Test
    @DisplayName("空输入 → 空串")
    void emptyInput_returnsEmptyString() {
        assertThat(SeatLabel.describe(List.of(), map(), SeatLabel.Fallback.SKIP_COMPACT)).isEmpty();
        assertThat(SeatLabel.describe(null, map(), SeatLabel.Fallback.SKIP_COMPACT)).isEmpty();
    }

    @Test
    @DisplayName("全部缺失且策略为跳过 → 空串(而不是只有一个「、」)")
    void allMissing_withSkip_returnsEmptyString() {
        String compact = SeatLabel.describe(List.of(40, 41), map(), SeatLabel.Fallback.SKIP_COMPACT);
        String positional = SeatLabel.describe(List.of(40, 41), map(), SeatLabel.Fallback.SKIP_POSITIONAL);

        assertThat(compact).isEmpty();
        // 位置口径下会留下两个分隔符 —— 这也是既有行为, 不动
        assertThat(positional).isEqualTo("、");
    }
}
