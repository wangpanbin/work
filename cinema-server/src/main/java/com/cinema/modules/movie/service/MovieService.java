package com.cinema.modules.movie.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieMapper movieMapper;

    /**
     * F1 搜索筛选分页查询.
     * <p>支持:
     * <ul>
     *   <li>keyword: 片名/描述 LIKE 模糊</li>
     *   <li>genre: 类型精确匹配</li>
     *   <li>region: 地区精确匹配</li>
     *   <li>status: 上下架</li>
     * </ul>
     */
    public Page<Movie> search(int page, int size, String keyword, String genre, String region, Integer status) {
        LambdaQueryWrapper<Movie> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(Movie::getTitle, keyword)
                    .or().like(Movie::getDescription, keyword));
        }
        qw.eq(StringUtils.hasText(genre), Movie::getGenre, genre)
          .eq(StringUtils.hasText(region), Movie::getRegion, region)
          .eq(status != null, Movie::getStatus, status)
          .orderByDesc(Movie::getCreatedAt);
        return movieMapper.selectPage(new Page<>(page, size), qw);
    }

    /** 兼容旧接口 — 仅按 status 过滤 */
    public Page<Movie> page(int page, int size, Integer status) {
        return search(page, size, null, null, null, status);
    }

    public Movie detail(Long id) {
        Movie movie = movieMapper.selectById(id);
        if (movie == null) {
            throw new BizException("影片不存在");
        }
        return movie;
    }
}
