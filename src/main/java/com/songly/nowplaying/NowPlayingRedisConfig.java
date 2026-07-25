package com.songly.nowplaying;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Nasłuch kanału presence — Spring Boot nie autokonfiguruje kontenera
 * nasłuchującego, więc subskrypcję trzeba założyć ręcznie (ADR 0007, Etap 2).
 * Kontener startuje na każdej instancji, dzięki czemu każda dostaje komplet
 * zmian statusu i sama decyduje, komu je lokalnie wypchnąć.
 */
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
