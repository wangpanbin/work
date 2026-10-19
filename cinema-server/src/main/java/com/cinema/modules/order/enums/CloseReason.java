package com.cinema.modules.order.enums;

/**
 * 待支付订单被关闭的原因。
 *
 * <p>关单协议本身(CAS → 释放座位 → 清缓存 → 发座位释放事件)只在
 * {@code PendingOrderCloser} 里写一份, 各调用点只声明「为什么关」。
 *
 * <p>三种原因对**管理端大屏**的可见性不同, 这是刻意区分的:
 * <ul>
 *   <li>{@link #CANCEL} —— 用户主动取消, 大屏要看到。</li>
 *   <li>{@link #TIMEOUT} —— 支付窗口超时, 大屏要看到。</li>
 *   <li>{@link #REPLACED} —— 同一用户在同一场次重新锁座, 旧待支付单被顶掉。
 *       <b>不发管理端大屏事件</b>: 这不是一次「订单消失」, 而是一次「订单被替换」,
 *       大屏统计的是锁座 / 售出 / 取消的流水, 顶替会重复计数。
 *       座位释放事件(RELEASED)照发 —— 座位确实回到可选池了。</li>
 * </ul>
 */
public enum CloseReason {

    /** 用户主动取消。 */
    CANCEL(true),

    /** 支付窗口超时, 由延迟队列扫描器关单。 */
    TIMEOUT(true),

    /** 同场次重新锁座, 旧待支付单被顶掉(不发管理端大屏事件)。 */
    REPLACED(false);

    private final boolean adminEvent;

    CloseReason(boolean adminEvent) {
        this.adminEvent = adminEvent;
    }

    /** 是否要发管理端大屏事件。 */
    public boolean publishesAdminEvent() {
        return adminEvent;
    }
}
