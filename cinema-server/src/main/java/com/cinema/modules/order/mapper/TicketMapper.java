package com.cinema.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.order.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
