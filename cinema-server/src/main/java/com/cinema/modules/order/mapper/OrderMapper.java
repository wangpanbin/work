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

    /** N1 CAS 进入退款: 仅已支付 → 退款中 */
    @Update("UPDATE `order` SET status = 3 WHERE order_no = #{orderNo} AND status = 1")
    int casMarkRefunding(@Param("orderNo") String orderNo);

    /** N1 CAS 完成退款: 仅退款中 → 已退款 */
    @Update("UPDATE `order` SET status = 4, refunded_at = NOW() WHERE order_no = #{orderNo} AND status = 3")
    int casMarkRefunded(@Param("orderNo") String orderNo);

    /** N1 补偿: 卡死退款单(REFUNDING 超 5min) → 已取消(降级,不入账) */
    @Update("UPDATE `order` SET status = 2 WHERE status = 3 AND updated_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)")
    int markStuckRefundingAsFailed();

    // ====================== O3 看板聚合 SQL ======================

    /** 今日票房: status=1 AND DATE(paid_at) = CURDATE() */
    @org.apache.ibatis.annotations.Select("SELECT IFNULL(SUM(total_amount), 0) FROM `order` " +
            "WHERE status = 1 AND DATE(paid_at) = CURDATE()")
    java.math.BigDecimal sumRevenueToday();

    /** 今日订单数(可按 status 过滤; null=全部) */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM `order` WHERE DATE(created_at) = CURDATE() " +
            "AND (#{status} IS NULL OR status = #{status})")
    Integer countToday(@Param("status") Integer status);

    /** 今日待支付座位数(锁座) */
    @org.apache.ibatis.annotations.Select("SELECT IFNULL(SUM(seat_count), 0) FROM `order` " +
            "WHERE status = 0 AND DATE(created_at) = CURDATE()")
    Integer sumTodayPendingSeats();

    /**
     * 今日已关单数(2=CANCELLED, 4=REFUNDED)
     * <p>按 updated_at 算, 因为关单会改 updated_at
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM `order` " +
            "WHERE status = #{status} AND DATE(updated_at) = CURDATE()")
    Integer countTodayClosed(@Param("status") Integer status);

    /** 7 日票房趋势 */
    @org.apache.ibatis.annotations.Select("SELECT DATE(paid_at) AS date, IFNULL(SUM(total_amount), 0) AS amount " +
            "FROM `order` WHERE status = 1 AND paid_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE(paid_at) ORDER BY date ASC")
    java.util.List<java.util.Map<String, Object>> weeklyTrend();

    /** TOP 5 影片(本周按票房) */
    @org.apache.ibatis.annotations.Select("SELECT m.title, IFNULL(SUM(o.total_amount), 0) AS revenue, COUNT(*) AS orders " +
            "FROM `order` o JOIN session s ON o.session_id = s.id " +
            "JOIN movie m ON s.movie_id = m.id " +
            "WHERE o.status = 1 AND o.paid_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY m.id, m.title ORDER BY revenue DESC LIMIT 5")
    java.util.List<java.util.Map<String, Object>> topMoviesWeek();

    /** 场次上座率(本周): 每个 session_id 的已售座位数 */
    @org.apache.ibatis.annotations.Select("SELECT s.id AS sessionId, IFNULL(SUM(o.seat_count), 0) AS sold " +
            "FROM session s LEFT JOIN `order` o ON o.session_id = s.id AND o.status = 1 " +
            "WHERE s.start_time BETWEEN DATE_SUB(NOW(), INTERVAL 7 DAY) AND DATE_ADD(NOW(), INTERVAL 1 DAY) " +
            "GROUP BY s.id ORDER BY sold DESC LIMIT 20")
    java.util.List<java.util.Map<String, Object>> sessionOccupancyWeek();
}
