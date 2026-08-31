package com.cinema.modules.movie.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MovieService 单测 — F1 搜索(行为级)+ detail. */
@ExtendWith(MockitoExtension.class)
class MovieServiceTest {

    @Mock private MovieMapper movieMapper;
    private MovieService service;

    @BeforeEach
    void setUp() {
        service = new MovieService(movieMapper);
    }

    @Test
    @DisplayName("搜索: 命中时 selectPage 被调用且返回结果")
    void search_basic() {
        Page<Movie> p = new Page<>(1, 10, 1);
        p.setRecords(java.util.List.of(new Movie()));
        when(movieMapper.selectPage(any(Page.class), any())).thenReturn(p);

        Page<Movie> result = service.search(1, 10, "地球", "科幻", "中国", 1);

        assertThat(result.getRecords()).hasSize(1);
        verify(movieMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("搜索: 全空入参也能调用(只按 status 过滤)")
    void search_allNull() {
        when(movieMapper.selectPage(any(Page.class), any())).thenReturn(new Page<>(1, 10, 0));

        Page<Movie> result = service.search(1, 10, null, null, null, 1);
        assertThat(result.getTotal()).isZero();
        verify(movieMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("page 兼容旧接口")
    void page_legacy() {
        when(movieMapper.selectPage(any(Page.class), any())).thenReturn(new Page<>(1, 10, 0));

        Page<Movie> result = service.page(1, 10, 1);
        assertThat(result.getTotal()).isZero();
    }

    @Test
    @DisplayName("详情: 不存在抛 BizException")
    void detail_notFound() {
        when(movieMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("影片不存在");
    }

    @Test
    @DisplayName("详情: 存在直接返回")
    void detail_found() {
        Movie m = new Movie();
        m.setId(1L);
        m.setTitle("流浪地球3");
        when(movieMapper.selectById(1L)).thenReturn(m);

        Movie result = service.detail(1L);
        assertThat(result.getTitle()).isEqualTo("流浪地球3");
    }
}
