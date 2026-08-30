package com.cinema.config;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.ws.SeatRedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;

@Configuration
@RequiredArgsConstructor
public class RedisSubscriberConfig {

    private final SeatRedisSubscriber seatRedisSubscriber;

    /** 绑定座位事件频道: seat:event → SeatRedisSubscriber → 本机 WS 推送 */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        Topic seatEventTopic = new ChannelTopic(RedisKeys.SEAT_EVENT_CHANNEL);
        container.addMessageListener(seatRedisSubscriber, seatEventTopic);
        return container;
    }
}
