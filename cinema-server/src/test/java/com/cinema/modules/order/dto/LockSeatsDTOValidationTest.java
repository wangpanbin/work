package com.cinema.modules.order.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 修复回归闸门(2026-10-11 稳定性测试): {@code LockSeatsDTO.seatIndexes} 的元素级约束。
 *
 * <p>修复前只有 {@code @NotEmpty} + {@code @Size(max=4)}, 没有任何元素级约束, 后果:
 * <ul>
 *   <li>{@code [null]} → {@code stream().sorted()} 抛 NPE → 落 50000「系统繁忙」</li>
 *   <li>{@code [-1]} → Redis {@code GETBIT} 报 {@code ERR bit offset ...} → 也落 50000</li>
 *   <li>{@code [100000]} → <b>被接受</b>, 进到 {@code lock_seat.lua} 的 {@code SETBIT},
 *       位图按 offset 立即分配且永不回收(实测 18B → 12501B, 放大 694 倍)</li>
 * </ul>
 *
 * <p>走 Jakarta {@link Validator} 而不是直接 new controller —— {@code @Valid} 是 Spring MVC 层的
 * 触发器, 不经 Validator 就测不到容器元素约束。
 *
 * <p>注意上界: {@code @Max(4095)} 只是兜底, <b>真实上界按场次 seatCount 算</b>
 * (1 号厅 96 座 / 2 号厅 140 座), 由 {@code OrderCore#requireSeatsInRange} 负责,
 * 见 {@code OrderCoreSeatRangeTest}。
 */
class LockSeatsDTOValidationTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private static LockSeatsDTO dto(List<Integer> seats) {
        LockSeatsDTO d = new LockSeatsDTO();
        d.setSessionId(1L);
        d.setSeatIndexes(seats);
        return d;
    }

    private static Set<String> messages(List<Integer> seats) {
        return validator.validate(dto(seats)).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("合法座位: 1~4 个非负索引无任何违规")
    void validSeats_passValidation() {
        assertThat(validator.validate(dto(List.of(0)))).isEmpty();
        assertThat(validator.validate(dto(List.of(0, 95, 139, 140 - 1)))).isEmpty();
    }

    @Test
    @DisplayName("P0 回归: null 座位元素被拒(修复前会 NPE 成 50000)")
    void nullSeatElement_rejected() {
        // List.of 不允许 null, 必须用 Arrays.asList
        assertThat(messages(Arrays.asList(10, null))).contains("座位索引不能为空");
    }

    @Test
    @DisplayName("P1 回归: 负数座位索引被拒(修复前会落 50000「系统繁忙」)")
    void negativeSeatElement_rejected() {
        assertThat(messages(List.of(-1))).contains("座位索引不能为负数");
        assertThat(messages(List.of(-99999))).contains("座位索引不能为负数");
    }

    @Test
    @DisplayName("兜底上界: 离谱的座位索引被 @Max 挡住")
    void absurdSeatElement_rejected() {
        assertThat(messages(List.of(100000))).contains("座位索引超出影厅最大座位数");
        assertThat(messages(List.of(4000000))).contains("座位索引超出影厅最大座位数");
    }

    @Test
    @DisplayName("空列表 / 超过 4 个座位仍按原契约被拒")
    void emptyOrTooMany_rejected() {
        assertThat(messages(new ArrayList<>())).contains("请至少选择一个座位");
        assertThat(messages(List.of(0, 1, 2, 3, 4))).contains("每次最多选择4个座位");
    }

    @Test
    @DisplayName("sessionId 为 null 仍被拒")
    void nullSessionId_rejected() {
        LockSeatsDTO d = new LockSeatsDTO();
        d.setSeatIndexes(List.of(1));
        assertThat(validator.validate(d).stream().map(ConstraintViolation::getMessage))
                .contains("场次不能为空");
    }
}
