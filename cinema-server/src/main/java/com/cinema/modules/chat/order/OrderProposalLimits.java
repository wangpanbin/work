package com.cinema.modules.chat.order;

/**
 * 对话助手下单的「最多几座」单一来源(ADR-0004)。
 *
 * <p>这个 4 原先散落在 {@code OrderProposalService.MAX_SEATS}、
 * {@code OrderProposalDTO.@Max} 与 {@code ReplyCardParser} 的校验里,
 * 而 {@code ReplyCardParser} 为了读一个常量还得 import 一个 Spring Service ——
 * 纯函数类被 bean 依赖拖累, 架构上说不通。
 *
 * <p>这里抽成无状态的常量类: 编译期常量, 可以直接进注解与 SpEL,
 * 任何模块引用它都不会牵出 bean 依赖。
 */
public final class OrderProposalLimits {

    private OrderProposalLimits() {}

    /** 单次最多几个座位 —— 与 {@code LockSeatsDTO @Size(max=4)} 和前端 maxSelect=4 一致。 */
    public static final int MAX_SEATS = 4;
}
