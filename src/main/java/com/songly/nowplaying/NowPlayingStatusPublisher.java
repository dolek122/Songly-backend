package com.songly.nowplaying;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class NowPlayingStatusPublisher {

	static final String CHANNEL = "now-playing:status-changed";
	static final String ANNOUNCED_KEY_PREFIX = "now-playing:announced:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final NowPlayingProperties properties;

	NowPlayingStatusPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			NowPlayingProperties properties) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	void publish(UUID userId, NowPlayingStatusResponse status) {
		NowPlayingStatusChangedMessage message = new NowPlayingStatusChangedMessage(userId, status);
		redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
		if (status == null) {
			redisTemplate.delete(ANNOUNCED_KEY_PREFIX + userId);
		} else {
			redisTemplate.opsForValue().set(ANNOUNCED_KEY_PREFIX + userId, "1", properties.getAnnouncedTtl());
		}
	}

	void publishStopped(UUID userId) {
		publish(userId, null);
	}
}
