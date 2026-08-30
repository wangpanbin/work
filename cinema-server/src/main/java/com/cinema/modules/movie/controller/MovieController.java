package com.cinema.modules.movie.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.result.R;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.service.MovieService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    @GetMapping
    public R<Page<Movie>> page(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size,
                               @RequestParam(required = false) Integer status) {
        return R.ok(movieService.page(page, size, status));
    }

    @GetMapping("/{id}")
    public R<Movie> detail(@PathVariable Long id) {
        return R.ok(movieService.detail(id));
    }
}
