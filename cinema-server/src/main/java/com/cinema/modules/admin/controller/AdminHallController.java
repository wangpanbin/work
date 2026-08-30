package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.hall.dto.HallDTO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
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
@RequestMapping("/api/admin/halls")
@RequiredArgsConstructor
public class AdminHallController {

    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @GetMapping
    public R<List<Hall>> list(@RequestParam(required = false) Long cinemaId) {
        LambdaQueryWrapper<Hall> qw = new LambdaQueryWrapper<Hall>()
                .eq(cinemaId != null, Hall::getCinemaId, cinemaId);
        return R.ok(hallMapper.selectList(qw));
    }

    @GetMapping("/{id}")
    public R<Hall> detail(@PathVariable Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        return R.ok(hall);
    }

    /**
     * 创建影厅: 同时按 行x列 自动生成座位(含 VIP 区), 整体在一个事务中
     */
    @PostMapping
    public R<Hall> create(@Valid @RequestBody HallDTO dto) {
        int count = dto.getSeatRows() * dto.getSeatCols();
        Hall hall = new Hall();
        hall.setCinemaId(dto.getCinemaId());
        hall.setName(dto.getName());
        hall.setSeatRows(dto.getSeatRows());
        hall.setSeatCols(dto.getSeatCols());
        hall.setSeatCount(count);
        Long hallId = transactionTemplate.execute(tx -> {
            hallMapper.insert(hall);
            insertSeats(hall, dto.getVipFromRow());
            return hall.getId();
        });
        hall.setId(hallId);
        return R.ok(hall);
    }

    @PutMapping("/{id}")
    public R<Hall> update(@PathVariable Long id, @Valid @RequestBody HallDTO dto) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        hall.setName(dto.getName());
        hall.setSeatRows(dto.getSeatRows());
        hall.setSeatCols(dto.getSeatCols());
        hall.setSeatCount(dto.getSeatRows() * dto.getSeatCols());
        transactionTemplate.executeWithoutResult(tx -> {
            hallMapper.updateById(hall);
            seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
            insertSeats(hall, dto.getVipFromRow());
        });
        redisTemplate.delete(RedisKeys.hallLayout(id));
        return R.ok(hall);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Hall hall = hallMapper.selectById(id);
        if (hall == null) {
            throw new BizException("影厅不存在");
        }
        transactionTemplate.executeWithoutResult(tx -> {
            seatMapper.delete(new LambdaQueryWrapper<Seat>().eq(Seat::getHallId, id));
            hallMapper.deleteById(id);
        });
        redisTemplate.delete(RedisKeys.hallLayout(id));
        return R.ok();
    }

    private void insertSeats(Hall hall, Integer vipFromRow) {
        int rows = hall.getSeatRows();
        int cols = hall.getSeatCols();
        long hallId = hall.getId();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                Seat seat = new Seat();
                seat.setId(hallId * 100000L + (long) (r - 1) * cols + (c - 1));
                seat.setHallId(hallId);
                seat.setSeatIndex((r - 1) * cols + (c - 1));
                seat.setRowNo(r);
                seat.setColNo(c);
                seat.setSeatType(vipFromRow != null && r >= vipFromRow ? 1 : 0);
                seat.setX(c * 40);
                seat.setY(r * 40);
                seatMapper.insert(seat);
            }
        }
    }
}