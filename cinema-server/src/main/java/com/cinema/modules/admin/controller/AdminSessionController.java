package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.session.dto.SessionDTO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final StringRedisTemplate redisTemplate;

    @GetMapping
    public R<List<Session>> list(@RequestParam(required = false) Long movieId) {
        LambdaQueryWrapper<Session> qw = new LambdaQueryWrapper<Session>()
                .eq(movieId != null, Session::getMovieId, movieId)
                .orderByAsc(Session::getStartTime);
        return R.ok(sessionMapper.selectList(qw));
    }

    @PostMapping
    public R<Session> create(@Valid @RequestBody SessionDTO dto) {
        Session s = new Session();
        applyDto(s, dto);
        if (s.getEndTime() == null) {
            Movie movie = movieMapper.selectById(dto.getMovieId());
            if (movie != null && movie.getDuration() != null) {
                s.setEndTime(s.getStartTime().plusMinutes(movie.getDuration()));
            }
        }
        sessionMapper.insert(s);
        redisTemplate.delete(RedisKeys.sessionInfo(s.getId()));
        return R.ok(s);
    }

    @PutMapping("/{id}")
    public R<Session> update(@PathVariable Long id, @Valid @RequestBody SessionDTO dto) {
        Session s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("场次不存在");
        }
        applyDto(s, dto);
        sessionMapper.updateById(s);
        redisTemplate.delete(RedisKeys.sessionInfo(s.getId()));
        return R.ok(s);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Session s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException("场次不存在");
        }
        sessionMapper.deleteById(id);
        redisTemplate.delete(RedisKeys.sessionInfo(s.getId()));
        redisTemplate.delete(RedisKeys.sessionLock(s.getId()));
        redisTemplate.delete(RedisKeys.sessionSold(s.getId()));
        return R.ok();
    }

    private void applyDto(Session s, SessionDTO dto) {
        s.setMovieId(dto.getMovieId());
        s.setHallId(dto.getHallId());
        s.setStartTime(dto.getStartTime());
        s.setEndTime(dto.getEndTime());
        s.setPrice(dto.getPrice());
        s.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }
}