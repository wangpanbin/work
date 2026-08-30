package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.modules.movie.dto.MovieDTO;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/movies")
@RequiredArgsConstructor
public class AdminMovieController {

    private final MovieMapper movieMapper;

    @GetMapping
    public R<Page<Movie>> page(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size,
                               @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Movie> qw = new LambdaQueryWrapper<Movie>()
                .like(keyword != null && !keyword.isBlank(), Movie::getTitle, keyword)
                .orderByDesc(Movie::getCreatedAt);
        return R.ok(movieMapper.selectPage(new Page<>(page, size), qw));
    }

    @PostMapping
    public R<Movie> create(@Valid @RequestBody MovieDTO dto) {
        Movie m = new Movie();
        applyDto(m, dto);
        movieMapper.insert(m);
        return R.ok(m);
    }

    @PutMapping("/{id}")
    public R<Movie> update(@PathVariable Long id, @Valid @RequestBody MovieDTO dto) {
        Movie m = movieMapper.selectById(id);
        if (m == null) {
            throw new BizException("影片不存在");
        }
        applyDto(m, dto);
        movieMapper.updateById(m);
        return R.ok(m);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Movie m = movieMapper.selectById(id);
        if (m == null) {
            throw new BizException("影片不存在");
        }
        movieMapper.deleteById(id);
        return R.ok();
    }

    private void applyDto(Movie m, MovieDTO dto) {
        m.setTitle(dto.getTitle());
        m.setPoster(dto.getPoster() == null ? "" : dto.getPoster());
        m.setDuration(dto.getDuration());
        m.setDescription(dto.getDescription() == null ? "" : dto.getDescription());
        m.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }
}