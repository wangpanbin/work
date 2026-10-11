package com.cinema.modules.admin.controller;

import com.cinema.common.exception.BizException;
import com.cinema.modules.movie.dto.MovieDTO;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 片名两道防护的回归闸门: 长度上限(Bean Validation) + 全表重名拦截(Service 侧查重).
 *
 * <p>体检发现(2026-10-11): {@code MovieDTO.title} 只有 {@code @NotBlank}, 没有长度上限;
 * {@code AdminMovieController.create/update} 直接落库, 没有重名拦截. 后者会让管理端
 * 反复提交同名影片, 首页与搜索结果出现无法区分的重复条目.
 *
 * <p>本类刻意用<b>两个独立 seam</b>, 不要合并:
 * <ul>
 *   <li><b>DTO 约束</b>走 Jakarta {@link Validator} —— 因为 {@code @Valid} 是 Spring MVC
 *       层的触发器, 直接 new controller 调 create() 根本不会跑校验; 不走 Validator
 *       就会写出"测了但没测到"的假绿.</li>
 *   <li><b>重名拦截</b>走 controller + Mockito mock {@link MovieMapper} —— 因为查重是
 *       Service 行为, 断言点是"抛 BizException 且不落库", 不是 Bean Validation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminMovieControllerTitleGuardTest {

    /** 片名长度上限, 与 movie.title 列宽(VARCHAR(128))保持一致. */
    private static final int TITLE_MAX = 128;

    private static Validator validator;

    @Mock
    private MovieMapper movieMapper;

    private AdminMovieController controller;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @BeforeEach
    void setUp() {
        controller = new AdminMovieController(movieMapper);
    }

    // ---------- 第一道: DTO 长度上限 ----------

    @Test
    @DisplayName("片名 129 字 → 触发 @Size 违规(超 1 字就拦)")
    void title_129chars_violatesSize() {
        Set<ConstraintViolation<MovieDTO>> violations = validator.validate(validDto(han(129)));

        assertThat(violations)
                .as("片名超过 128 字必须被拦下, 否则超长片名会写进 VARCHAR(128) 列")
                .anyMatch(v -> "片名最长 128 个字符".equals(v.getMessage()));
    }

    @Test
    @DisplayName("片名 128 字 → 无违规(边界含端点, 不能少拦一个)")
    void title_128chars_passesSize() {
        assertThat(validator.validate(validDto(han(TITLE_MAX))))
                .as("正好 128 字必须放行, @Size(max=128) 是闭区间")
                .isEmpty();
    }

    @Test
    @DisplayName("片名空串 → 仍触发 @NotBlank(不能被 @Size 抢跑吞掉)")
    void title_blank_stillTriggersNotBlank() {
        Set<ConstraintViolation<MovieDTO>> violations = validator.validate(validDto("   "));

        assertThat(violations)
                .as("@Size 加上后不能影响 @NotBlank 的既有行为")
                .anyMatch(v -> "片名不能为空".equals(v.getMessage()));
    }

    // ---------- 第二道: 重名拦截 ----------

    @Test
    @DisplayName("片名已存在 → create 抛 BizException 且不落库")
    void create_duplicateTitle_throwsAndDoesNotInsert() {
        when(movieMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> controller.create(validDto("哪吒之魔童闹海")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("片名已存在");

        verify(movieMapper, never()).insert(any(Movie.class));
    }

    @Test
    @DisplayName("片名不存在 → create 正常 insert")
    void create_freshTitle_inserts() {
        when(movieMapper.selectCount(any())).thenReturn(0L);

        assertThatCode(() -> controller.create(validDto("一部全新的片子"))).doesNotThrowAnyException();

        verify(movieMapper).insert(any(Movie.class));
    }

    @Test
    @DisplayName("改片名撞上别的影片 → update 抛 BizException 且不 update")
    void update_duplicateTitle_throwsAndDoesNotUpdate() {
        when(movieMapper.selectById(1L)).thenReturn(existingMovie(1L));
        when(movieMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> controller.update(1L, validDto("流浪地球3")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("片名已存在");

        verify(movieMapper, never()).updateById(any(Movie.class));
    }

    @Test
    @DisplayName("update 保持原片名不变 → 排除自身 id, 不误判重名")
    void update_unchangedTitle_excludesSelf() {
        when(movieMapper.selectById(1L)).thenReturn(existingMovie(1L));
        // 查重带上 ne(id, 1) 之后必然查不到自己 → count 0
        when(movieMapper.selectCount(any())).thenReturn(0L);

        assertThatCode(() -> controller.update(1L, validDto("流浪地球3"))).doesNotThrowAnyException();

        verify(movieMapper).updateById(any(Movie.class));
    }

    @Test
    @DisplayName("影片不存在 → update 仍先报'影片不存在', 不落到重名分支")
    void update_missingMovie_reportsMissingFirst() {
        when(movieMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> controller.update(99L, validDto("查无此片")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("影片不存在");

        verify(movieMapper, never()).selectCount(any());
    }

    // ---------- helpers ----------

    /** 构造一个除片名外全部合法的 DTO, 让校验结果只反映片名这一项. */
    private static MovieDTO validDto(String title) {
        MovieDTO dto = new MovieDTO();
        dto.setTitle(title);
        dto.setDuration(120);
        dto.setStatus(1);
        dto.setGenre("剧情");
        dto.setRegion("中国大陆");
        dto.setReleaseDate(LocalDate.of(2026, 1, 1));
        return dto;
    }

    private static Movie existingMovie(Long id) {
        Movie m = new Movie();
        m.setId(id);
        m.setTitle("流浪地球3");
        return m;
    }

    /** 造 n 个汉字 —— 汉字在 BMP 里占 1 个 char, String.length() 与数据库字符数一致. */
    private static String han(int n) {
        return "影".repeat(n);
    }
}