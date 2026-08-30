package com.cinema.modules.movie.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieMapper movieMapper;

    public Page<Movie> page(int page, int size, Integer status) {
        LambdaQueryWrapper<Movie> qw = new LambdaQueryWrapper<Movie>()
                .eq(status != null, Movie::getStatus, status)
                .orderByDesc(Movie::getCreatedAt);
        return movieMapper.selectPage(new Page<>(page, size), qw);
    }

    public Movie detail(Long id) {
        Movie movie = movieMapper.selectById(id);
        if (movie == null) {
            throw new BizException("影片不存在");
        }
        return movie;
    }
}
