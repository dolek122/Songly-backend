package com.songly.nowplaying;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class NowPlayingRedisConfig {

	@Bean
	RedisMessageListenerContainer nowPlayingStatusListenerContainer(RedisConnectionFactory connectionFactory,
			NowPlayingStatusSubscriber subscriber) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(NowPlayingStatusPublisher.CHANNEL));
		return container;
	}
}
