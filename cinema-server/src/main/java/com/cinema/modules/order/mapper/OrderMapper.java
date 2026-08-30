package com.cinema.modules.order.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    default Order selectByOrderNo(String orderNo) {
        return selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderNo, orderNo));
    }

    /** CAS 支付: 仅待支付 → 已支付 */
    @Update("UPDATE `order` SET status = 1, paid_at = NOW() WHERE order_no = #{orderNo} AND status = 0")
    int casMarkPaid(@Param("orderNo") String orderNo);

    /** CAS 取消: 仅待支付 → 已取消(支付/关单互斥的关键) */
    @Update("UPDATE `order` SET status = 2 WHERE order_no = #{orderNo} AND status = 0")
    int casCancel(@Param("orderNo") String orderNo);
}
