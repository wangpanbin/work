package com.cinema.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
}
