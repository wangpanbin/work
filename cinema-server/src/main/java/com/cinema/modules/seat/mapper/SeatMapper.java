package com.cinema.modules.seat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.seat.entity.Seat;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SeatMapper extends BaseMapper<Seat> {
}
