package com.cinema.modules.seat.reader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.vo.HallLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 影厅布局缓存: Redis JSON 1h 缓存 + miss 时查 DB 回填.
 *
 * <p>#4 收尾: 抽自 SeatService.hallLayout. 管理端改动影厅时通过 RedisKeys.hallLayout(hallId) 失效.
 */
@Component
@RequiredArgsConstructor
public class HallLayoutCache {

    private final StringRedisTemplate redisTemplate;
    private final SeatMapper seatMapper;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public HallLayout load(Long hallId, int rows, int cols) {
        String key = RedisKeys.hallLayout(hallId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, HallLayout.class);
        }
        List<Integer> vipRows = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hallId)
                        .eq(Seat::getSeatType, 1))
                .stream().map(Seat::getRowNo).distinct().sorted().toList();
        HallLayout layout = new HallLayout(rows, cols, vipRows);
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(layout), Duration.ofHours(1));
        return layout;
    }
}