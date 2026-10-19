package com.cinema.modules.order.service;

import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.CloseReason;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 订单取消 / 超时关单 —— 触发待支付订单关闭的两种业务原因。
 *
 * <p>E1 拆分: 负责主动取消(cancel) + 扫描器/补偿触发的 closeIfUnpaid。
 * 复用 OrderCore 做状态校验和座位索引读取。
 *
 * <p><b>关单协议本身不在这里</b>: CAS → 释放座位 → 清缓存 → 发事件的完整序列
 * 由 {@link PendingOrderCloser} 统一实现, 本类只负责「判定该不该关」与「声明为什么关」。
 */
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderMapper orderMapper;
    private final OrderCore orderCore;
    private final PendingOrderCloser pendingOrderCloser;

    /** 主动取消 */
    public void cancel(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        orderCore.requirePending(order);
        pendingOrderCloser.close(order, CloseReason.CANCEL);
    }

    /**
     * 幂等关单(供超时扫描/补偿任务复用): CAS(0→2) 成功才释放座位, 重复投递天然安全
     */
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            return false;
        }
        // 一次查 order_item, 复用给关单流程
        order.setSeatIndexCache(orderCore.seatIndexesOf(order.getId()));
        return pendingOrderCloser.close(order, CloseReason.TIMEOUT);
    }
}
