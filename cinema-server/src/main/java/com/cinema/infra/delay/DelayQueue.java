package com.cinema.infra.delay;

import java.util.List;

/**
 * 延迟队列抽象 —— 屏蔽底层实现(Redis ZSet / RabbitMQ)
 *
 * <p>背景: 本机无 RabbitMQ, 采用 Redis ZSet(score=到期时间戳)实现延迟关单;
 * RabbitMQ(TTL+死信)实现保留为扩展点, 业务层只依赖本接口。
 * 消费语义为 at-least-once, 消费方必须幂等(关单靠 DB CAS 保证)。</p>
 */
public interface DelayQueue {

    /** 投递一条延迟消息, delayMillis 后可被消费 */
    void offer(String message, long delayMillis);

    /** 取出所有已到期消息(取出即从队列移除, 失败由消费方幂等兜底) */
    List<String> pollExpired(int batch);

    /** 实现标识, 用于日志与压测报告 */
    String name();
}
