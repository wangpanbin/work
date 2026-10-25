package com.cinema.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.order.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 一次性验票的**原子**标记(2026-10-24)。
     *
     * <p>验票的语义是「这张票只能被用一次」。原先的实现是读-改-写:
     * {@code if (verifiedAt != null) return used;} → {@code setVerifiedAt} → {@code updateById}。
     * 两个检票员并发扫同一张票时, 两次读都看到 {@code verified_at IS NULL},
     * 两次写都成功 → 两边都拿到 {@code valid=true} → <b>一票两用</b>。
     *
     * <p>改成条件更新: 只有 {@code verified_at IS NULL} 的那一行会被改,
     * 影响行数即「本票归我了」。并发下只有一个 UPDATE 影响 1 行, 另一个影响 0 行 → 返「已使用」。
     *
     * <p>与 {@code OrderMapper} 的 CAS 同构: 状态推进靠 WHERE 条件, 不靠先读后写。
     *
     * @return 影响行数:1 = 标记成功(本票归本次验票);0 = 已被别人验过
     */
    @Update("UPDATE `ticket` SET verified_at = #{verifiedAt} WHERE order_no = #{orderNo} AND verified_at IS NULL")
    int markVerifiedIfUnused(@Param("orderNo") String orderNo, @Param("verifiedAt") LocalDateTime verifiedAt);
}
