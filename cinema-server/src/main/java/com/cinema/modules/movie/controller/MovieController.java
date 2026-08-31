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

    /**
     * F1 搜索 + 筛选:
     * <ul>
     *   <li>keyword: 片名/描述模糊</li>
     *   <li>genre: 类型精确</li>
     *   <li>region: 地区精确</li>
     *   <li>status: 0下架 1热映</li>
     * </ul>
     */
    @GetMapping
    public R<Page<Movie>> search(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String genre,
                                 @RequestParam(required = false) String region,
                                 @RequestParam(required = false) Integer status) {
        return R.ok(movieService.search(page, size, keyword, genre, region, status));
    }

    @GetMapping("/{id}")
    public R<Movie> detail(@PathVariable Long id) {
        return R.ok(movieService.detail(id));
    }
}
